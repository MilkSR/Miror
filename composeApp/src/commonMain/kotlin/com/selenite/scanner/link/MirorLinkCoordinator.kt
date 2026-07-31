package com.selenite.scanner.link

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * Owns exactly one Miror Link session.
 *
 * Lives above Compose so a configuration change cannot produce two sessions driving
 * the radios at once -- the same hazard `ContentUpdateManager` documents for content
 * downloads, where a theme change at sunset destroys the Activity mid-transfer.
 *
 * Every callback is tagged with a monotonically increasing generation. A late event
 * from a session that has already ended is discarded rather than mutating live state,
 * which is what makes cancel/background/restart safe at any point.
 *
 * Collection success and content success are independent: a declined, incompatible,
 * or failed content update never turns an already-received collection into a failed
 * Link.
 */
class MirorLinkCoordinator internal constructor(
    private val transport: MirorLinkTransport,
    private val snapshots: MirorLinkSnapshotSource,
    private val lifecycle: MirorLinkLifecycle,
    private val contentGateway: MirorLinkContentGateway?,
    private val scope: CoroutineScope,
    private val nicknameProvider: () -> String,
    private val appBuild: String?,
    private val photoCodec: MirorLinkPhotoCodec? = null,
    private val sharePhotos: () -> Boolean = { false },
    private val onLinkStarted: () -> Unit = {},
    private val proximitySource: MirorLinkProximitySource? = null,
    private val proximity: MirorLinkProximityResolver = MirorLinkProximityResolver(),
    private val receivePhotos: () -> Boolean = { true },
    private val stabilityWindowMillis: Long = DEFAULT_STABILITY_WINDOW_MILLIS,
    private val chooserDelayMillis: Long = DEFAULT_CHOOSER_DELAY_MILLIS,
    private val inboundHoldMillis: Long = DEFAULT_INBOUND_HOLD_MILLIS,
    private val initiationFallbackMillis: Long = DEFAULT_INITIATION_FALLBACK_MILLIS,
    private val connectionFailureGraceMillis: Long = DEFAULT_CONNECTION_FAILURE_GRACE_MILLIS,
    private val radioStartupTimeoutMillis: Long = DEFAULT_RADIO_STARTUP_TIMEOUT_MILLIS,
    private val radioRestartCooldownMillis: Long = DEFAULT_RADIO_RESTART_COOLDOWN_MILLIS,
    private val discoveryTimeoutMillis: Long = DEFAULT_DISCOVERY_TIMEOUT_MILLIS,
    private val inactivityTimeoutMillis: Long = DEFAULT_INACTIVITY_TIMEOUT_MILLIS,
    private val prewarmTimeoutMillis: Long = DEFAULT_PREWARM_TIMEOUT_MILLIS,
    private val contentTransferTimeoutMillis: Long = DEFAULT_CONTENT_TRANSFER_TIMEOUT_MILLIS,
    private val viewingIdleTimeoutMillis: Long = DEFAULT_VIEWING_IDLE_TIMEOUT_MILLIS,
    private val sendTimeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
    private val nowMillis: () -> Long = defaultClock(),
) {
    private val _state = MutableStateFlow(MirorLinkUiState())
    val state: StateFlow<MirorLinkUiState> = _state.asStateFlow()

    private val mutex = Mutex()
    /**
     * Serializes every operation which can begin Nearby discovery/advertising.
     *
     * `shutdown()` is deliberately allowed to run outside this mutex so cancel/background
     * can interrupt a pending platform task. A replacement startup cannot overtake that
     * task before either its terminal callback or the bounded startup deadline: the old
     * startup retains this mutex through its ownership re-check and final cleanup, then the
     * replacement starts against a reconciled transport.
     */
    private val radioStartupMutex = Mutex()
    /**
     * A platform startup crossed its hard deadline, so its eventual completion time is
     * unknowable. Further starts in this process fail fast rather than overlap that retired
     * operation; Android also cleans the old operation if its Task eventually completes.
     * Process recreation supplies a fresh transport and clears this exceptional state.
     *
     * Guarded by [radioStartupMutex].
     */
    private var radioStartupPoisoned = false
    /** Serializes start/stop calls into platform proximity implementations. */
    private val proximityPlatformMutex = Mutex()
    private var generation = 0
    private var session: Session? = null
    /** Last non-prewarm session teardown; guarded by [mutex]. */
    private var lastRadioTeardownAt: Long? = null
    /**
     * Recent nonces minted by this coordinator process; guarded by [mutex].
     *
     * Nearby can briefly rediscover this phone's retired advertisement after a rapid
     * stop/start. Endpoint ids change, nicknames are not identities, but the advertised
     * nonce is exact. Remembering only our own bounded recent nonces lets every candidate
     * ingress discard that stale self without creating a stable device identifier.
     */
    private val localSessionNonceKeys = LinkedHashSet<String>()
    private var eventJob: Job? = null
    private var lifecycleJob: Job? = null
    private val proximitySamples = MirorLinkProximitySampleInbox()

    init {
        // Platform BLE callbacks may arrive on any thread. This is their only route into
        // mutable Link state: the bounded inbox supplies backpressure and this consumer
        // joins the same mutex domain as decide(), clear(), and every session transition.
        if (proximitySource != null) {
            scope.launch {
                while (true) {
                    val sample = proximitySamples.receive()
                    try {
                        mutex.withLock {
                            val current = session
                            if (current?.generation == sample.generation &&
                                current.proximityScanEpoch == sample.scanEpoch
                            ) {
                                proximity.record(sample.nonceKey, sample.rssiDbm, sample.atMillis)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // One unexpected observation must not retire proximity ranking for
                        // every later Link session in this process. No peer values are logged.
                        mirorLinkLog("proximity sample ignored")
                    }
                }
            }
        }
    }

    /** Discovery is running ahead of an actual session. See [prewarm]. */
    private var prewarming = false

    /**
     * Peers discovered while pre-warming, replayed into the session when one starts.
     *
     * Nearby raises `onEndpointFound` exactly once per endpoint. Dropping those callbacks
     * because no session existed yet meant a pre-warmed phone could never learn about a
     * peer it had already seen -- it sat on "Looking nearby" indefinitely while the other
     * side waited to be called. Found on hardware; the buffer is the fix.
     */
    private val prewarmDiscovered = LinkedHashMap<String, MirorLinkEndpointInfo>()

    /** When the current pre-warm began, for its own deadline. */
    private var prewarmStartedAt = 0L
    /** Distinguishes a late platform completion from the latest pre-warm request. */
    private var prewarmEpoch = 0

    private class Session(
        val generation: Int,
        val localNonce: ByteArray,
        val entryIds: Set<Long>?,
    ) {
        var outgoing: MirorLinkOutgoingSnapshot? = null

        /**
         * Completed when the collection snapshot has been encoded.
         *
         * Radio start used to sit behind the build, which put the entire
         * read-collection-and-encode cost on the critical path before the two phones
         * could even see each other. Discovery does not need the snapshot -- only
         * SNAPSHOT_OFFER does -- so the build now runs alongside discovery and the
         * sender simply awaits this before offering.
         */
        val outgoingReady = kotlinx.coroutines.CompletableDeferred<MirorLinkOutgoingSnapshot>()

        val candidates = LinkedHashMap<String, MirorLinkSelection.Candidate>()
        var selected: MirorLinkSelection.Candidate? = null
        var sessionId: ByteArray? = null
        var connectedEndpoint: String? = null
        var messageId = 0
        var lastPeerMessageId = -1
        var lastCandidateChangeAt = 0L
        var discoveryStartedAt = 0L

        /**
         * Last moment this session made observable progress -- connected, or moved a
         * frame in either direction. Drives the inactivity deadline, which is what stops
         * a peer holding a connection open forever by simply going quiet.
         */
        var lastActivityAt = 0L
        var chooserShown = false
        var manualChoiceKey: String? = null

        /** Invalidates timers belonging to a selection that has since been replaced. */
        var selectionEpoch = 0
        /** One connection-initiation fallback per selection. See `runInitiationFallback`. */
        var initiationFallbackUsed = false
        /**
         * The platform has refused or failed the selected endpoint's connection attempt.
         *
         * A different endpoint may represent the same phone after a rapid restart, but it
         * may also be an unrelated third phone. Time elapsed alone is not evidence that the
         * chosen peer is gone; this bit is set only by an actual Task/callback failure and
         * is therefore the gate for cross-endpoint restart recovery.
         */
        var selectedConnectionAttemptFailed = false
        /** At most one delayed platform-failure reconciliation per selection epoch. */
        var failureResolutionEpoch: Int? = null

        /**
         * Failed attempts for each advertised session identity.
         *
         * Nearby can reuse one endpoint id when a phone stops and immediately starts Link
         * again. The nonce, not the endpoint id, distinguishes those sessions. Keying only
         * by endpoint id retired the replacement alongside the dead session and explained
         * why rapid-restart recovery still needed a user tap in roughly half of hardware
         * runs.
         *
         * One entry per endpoint keeps the map bounded; a changed nonce replaces the old
         * record. [totalEndpointFailures] independently bounds a peer that rotates nonces
         * simply to obtain fresh retry budgets.
         */
        val endpointFailures = LinkedHashMap<String, EndpointFailure>()
        var totalEndpointFailures = 0
        /**
         * Endpoint identities whose Nearby verification callback this coordinator accepted.
         *
         * Kept across a selection restart because the platform connection may complete just
         * after a racing failure cleared `selected`. A known/discovered candidate alone is
         * not sufficient to adopt that late callback.
         */
        val admittedEndpointNonces = LinkedHashMap<String, String>()
        /** Invalidates a platform callback that arrives after selection restarts. */
        var proximityScanEpoch = 0

        var peerHello: HelloMessage? = null
        var helloSent = false
        /**
         * At most one pre-HELLO identity repair may replace the selected peer nonce.
         *
         * Nearby can complete a connection from an advertisement the peer retired during a
         * rapid restart. The peer's HELLO is the first transport-ordered proof of its fresh
         * nonce. Reconciliation is safe only before either side has accepted protocol data,
         * and it must not become a peer-controlled identity-rotation loop.
         */
        var helloIdentityReconciled = false
        /** One bounded HELLO retransmission when the peer first addresses a retired session. */
        var staleHelloResponseSent = false
        /** Separate replay window for a HELLO addressed to the just-retired local session. */
        var staleHelloSessionId: ByteArray? = null
        var lastStaleHelloMessageId = -1
        var snapshotSent = false
        var assembler: MirorLinkSnapshotAssembler? = null
        var peerOffer: SnapshotOfferMessage? = null
        var incomingTerminal = false
        var outgoingTerminal = false

        var contentOffer: ContentOfferMessage? = null

        /**
         * The offer the user was actually shown, frozen at the moment it was surfaced.
         *
         * Consent is to a specific version and size, not to "whatever the peer most
         * recently said". Without this, a second offer could replace the pending one
         * while the prompt was on screen and Accept would install the replacement --
         * still genuine Miror content, since the signature holds, but not the release the
         * user agreed to.
         */
        var acceptedOffer: ContentOfferMessage? = null
        var outgoingContentOffered = false
        var outgoingContentDescriptor: ContentOfferMessage? = null
        var outgoingContent: MirorLinkOutgoingContent? = null

        /**
         * The receiver gave up on the package this side is sending.
         *
         * Checked at every step of the outgoing stream. Without it a receiver that rejected
         * the transfer -- because a chunk was out of order, because staging failed, or
         * because the absolute deadline expired -- still had the whole ~57 MiB read off
         * disk and pushed at it, all of it discarded on arrival.
         */
        var outgoingContentAborted = false

        /**
         * The coroutine streaming the outgoing package, so an abort can interrupt a step
         * that is parked on the transport rather than only one between steps.
         */
        var outgoingContentJob: Job? = null
        var staging: MirorLinkContentStaging? = null
        var expectedContentFiles: List<MirorLinkContentFile> = emptyList()
        var activeContentFileIndex: Int? = null
        var nextContentFileIndex = 0
        var nextContentSequence = 0
        var activeContentFileBytes = 0L

        /**
         * Size of the file currently being received, taken from `CONTENT_FILE_BEGIN`.
         *
         * Recorded even when the original offer omitted sizes -- a bundle-only donor
         * cannot cheaply size its files, but it always declares them here. Without this
         * the per-chunk length could not be computed for those transfers, which is
         * exactly where the slow-loris lived.
         */
        var activeContentFileExpected = 0L

        /** Chunks the declared size implies, so the sequence cannot run past the file. */
        var activeContentChunkCount = 0L

        /**
         * Wall clock from acceptance. Deliberately never refreshed by traffic: a peer
         * that trickles perfectly-formed chunks is still holding the radios, the staging
         * directory and the screen open, and no honest transfer needs longer than this.
         */
        var contentStartedAt = 0L
        var contentBytesReceived = 0L
        var contentBytesExpected = 0L
        var contentTerminal = false
        var scanning = false

        /** Photo assembly in progress, keyed by card id. */
        val photoAssembly = LinkedHashMap<String, PhotoAssembly>()
        val photoRequested = LinkedHashSet<String>()

        /**
         * Card ids this side has already answered, and how many serves are in flight.
         *
         * Serving is real work -- a disk read, a decode, a downscale and a re-encode --
         * started straight from an inbound frame. Without a bound, a peer can ask for the
         * same card forever and have this phone do it every time. Answering each card once
         * is also simply correct: the answer cannot change within a session.
         */
        val photoServed = LinkedHashSet<String>()
        var photoServesInFlight = 0

        /** Cards a response has already been accepted for, so repeats are ignored. */
        val photoAnswered = LinkedHashSet<String>()

        val pendingInbound = LinkedHashMap<String, Long>()
        /** Prevents an old hold timer acting on a later request reusing an endpoint id. */
        var nextInboundToken = 0L

        fun nextMessageId(): Int = ++messageId
    }

    private data class EndpointFailure(
        val nonceKey: String,
        val attempts: Int,
    )

    /** Bounded reassembly for one photo, sized from its already-validated response. */
    private class PhotoAssembly(val response: PhotoResponseMessage) {
        val buffer = ByteArray(response.totalBytes)
        val received = BooleanArray(response.chunkCount)
        var count = 0

        val isComplete: Boolean get() = count == response.chunkCount

        fun accept(chunk: PhotoChunkMessage): Boolean {
            linkRequire(chunk.index in 0 until response.chunkCount) {
                "Photo chunk index is outside the offered range"
            }
            val start = chunk.index * MirorLinkProtocol.MAX_PHOTO_CHUNK_BYTES
            val expected = minOf(
                MirorLinkProtocol.MAX_PHOTO_CHUNK_BYTES,
                response.totalBytes - start,
            )
            linkRequire(chunk.data.size == expected) { "Photo chunk has the wrong length" }
            if (received[chunk.index]) return false
            chunk.data.copyInto(buffer, start)
            received[chunk.index] = true
            count++
            return true
        }

        fun finish(): ByteArray {
            linkRequire(isComplete) { "Photo is missing chunks" }
            linkRequire(MirorSha256.hash(buffer).contentEquals(response.sha256)) {
                "Photo failed its transfer integrity check"
            }
            return buffer
        }
    }

    // ---------------------------------------------------------------- entry points

    /**
     * Brings **discovery only** up before the user commits to linking.
     *
     * Nearby takes roughly two seconds to stand its stack up, which is most of the wait
     * between tapping Link and seeing a peer. Starting discovery while the share sheet is
     * open pays that down in advance, so the tap lands on already-warm radios.
     *
     * Deliberately not advertising: an un-committed phone must stay invisible, or a peer
     * already in Link could try to connect to someone who has not asked to share anything.
     * Selection is likewise not running -- this only collects the radio warm-up.
     *
     * The caller gates this on the user having completed Link at least once, so the first
     * permission prompt still arrives immediately after an explicit Miror Link tap.
     */
    fun prewarm() {
        scope.launch {
            val epochAtStart = mutex.withLock {
                if (session != null || prewarming) return@withLock null
                if (transport.availability() != MirorLinkAvailability.Available) return@withLock null
                prewarming = true
                prewarmEpoch++
                prewarmDiscovered.clear()
                prewarmStartedAt = nowMillis()
                // Collect during the warm-up too, or the discoveries it exists to obtain
                // are dropped on the floor.
                observeTransport()
                // A pre-warm is not a session, but it is still a live radio, so it needs
                // the same two guarantees a session has: it stops when the app leaves the
                // foreground, and it stops on its own. Relying on the sheet's dismiss
                // callback alone leaves discovery running whenever that callback does not
                // arrive -- a sheet left open, or a background without dismissal.
                observeLifecycle()
                prewarmEpoch
            }
            if (epochAtStart == null) return@launch
            mirorLinkLog("prewarming discovery")
            val started = radioStartupMutex.withLock radioLock@{
                if (radioStartupPoisoned) {
                    return@radioLock Result.failure(
                        IllegalStateException("radio startup unavailable for this process"),
                    )
                }
                // A session may have promoted or a stop may have retired this pre-warm
                // while it waited behind an older session's startup cleanup.
                val stillOwned = mutex.withLock {
                    prewarming && session == null && prewarmEpoch == epochAtStart
                }
                if (!stillOwned) {
                    null
                } else {
                    val cooldownRemaining = mutex.withLock { radioCooldownRemainingLocked() }
                    if (cooldownRemaining > 0) delay(cooldownRemaining)
                    val ownedAfterCooldown = mutex.withLock {
                        prewarming && session == null && prewarmEpoch == epochAtStart
                    }
                    if (!ownedAfterCooldown) return@radioLock null
                    val result = runCatching {
                        kotlinx.coroutines.withTimeout(radioStartupTimeoutMillis) {
                            transport.startDiscovery()
                        }
                    }
                    if (result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException) {
                        radioStartupPoisoned = true
                    }
                    result
                }
            } ?: return@launch
            var runDeadline = false
            mutex.withLock {
                if (prewarming && session == null && prewarmEpoch == epochAtStart) {
                    if (started.isSuccess) {
                        runDeadline = true
                    } else {
                        prewarming = false
                        prewarmDiscovered.clear()
                        eventJob?.cancel()
                        eventJob = null
                        lifecycleJob?.cancel()
                        lifecycleJob = null
                        runCatching { transport.shutdown() }
                    }
                } else {
                    // A newer pre-warm, or a promoted session still selecting, wants
                    // discovery left up. A promoted session which has selected a peer
                    // needs only discovery stopped -- shutting the whole transport down
                    // would kill its live/pending connection. With no owner left, release
                    // the whole transport.
                    val activeSession = session
                    when {
                        prewarming -> Unit
                        activeSession != null && activeSession.selected != null ->
                            runCatching { transport.stopDiscovery() }
                        activeSession == null -> runCatching { transport.shutdown() }
                    }
                }
            }
            if (runDeadline) runPrewarmDeadline()
        }
    }

    /** Bounds how long an uncommitted phone may keep discovery alive. */
    private suspend fun runPrewarmDeadline() {
        while (true) {
            delay(DEADLINE_POLL_INTERVAL_MILLIS)
            val expired = mutex.withLock {
                if (!prewarming || session != null) return
                nowMillis() - prewarmStartedAt >= prewarmTimeoutMillis
            }
            if (expired) {
                mirorLinkLog("prewarm expired; releasing discovery")
                stopPrewarm()
                return
            }
        }
    }

    /** Drops a pre-warm that never became a session. */
    fun stopPrewarm() {
        scope.launch {
            mutex.withLock {
                if (!prewarming || session != null) return@withLock
                prewarming = false
                prewarmDiscovered.clear()
                eventJob?.cancel()
                eventJob = null
                lifecycleJob?.cancel()
                lifecycleJob = null
                // Kept under the state mutex: once ownership is checked, a new session
                // must not slip in before this unconditional cleanup reaches the transport.
                runCatching { transport.shutdown() }
            }
        }
    }

    fun start(entryIds: Set<Long>?) {
        scope.launch { startInternal(entryIds) }
    }

    private suspend fun startInternal(entryIds: Set<Long>?) {
        var startedGeneration: Int? = null
        mutex.withLock {
            // A pre-warm has no session, so the only thing teardown would do to the radios
            // is stop the discovery we deliberately started early.
            val promotingPrewarm = prewarming && session == null
            teardownLocked(MirorLinkPhase.Idle, null, preserveRadios = promotingPrewarm)
            prewarming = false
            contentGateway?.cleanupAbandonedStaging()

            val availability = transport.availability()
            if (availability != MirorLinkAvailability.Available) {
                _state.value = MirorLinkUiState(
                    phase = MirorLinkPhase.PermissionSetup,
                    availability = availability.toUi(),
                    nickname = nicknameProvider(),
                )
                return
            }

            onLinkStarted()
            generation++
            val current = Session(
                generation = generation,
                localNonce = mirorLinkRandomBytes(MirorLinkProtocol.NONCE_BYTES),
                entryIds = entryIds,
            )
            rememberLocalNonceLocked(current.localNonce)
            current.discoveryStartedAt = nowMillis()
            current.lastCandidateChangeAt = current.discoveryStartedAt
            current.lastActivityAt = current.discoveryStartedAt
            // Hand the session everything the pre-warm already found.
            if (promotingPrewarm && prewarmDiscovered.isNotEmpty()) {
                var seeded = 0
                prewarmDiscovered.forEach { (endpointId, info) ->
                    if (trackCandidateLocked(current, endpointId, info)) seeded++
                }
                if (seeded > 0) mirorLinkLog("seeded $seeded pre-warmed peer(s)")
            }
            prewarmDiscovered.clear()
            session = current
            startedGeneration = current.generation

            _state.value = MirorLinkUiState(
                phase = MirorLinkPhase.Discovering,
                availability = MirorLinkAvailabilityUi.Ready,
                nickname = nicknameProvider(),
            )

            observeTransport()
            observeLifecycle()
        }

        // Snapshot generation runs in parallel with discovery so the user never waits on
        // a blank screen while the radios warm up.
        val generationAtStart = startedGeneration ?: return
        scope.launch { prepareSnapshot(generationAtStart, entryIds) }
        scope.launch { runDiscovery() }
        scope.launch { runSessionDeadlines(generationAtStart) }
    }

    /**
     * The user did something that means they are still using the shared collection --
     * opening a card, paging, deliberate browsing.
     *
     * The viewing budget measures *use*, not traffic. Peer frames cannot extend it on
     * their own, and neither can recomposition; only a deliberate act by the person
     * holding the phone. Ignored unless a session is live and the exchange has finished,
     * so it can never prolong a stalled handshake.
     */
    fun noteViewingActivity() {
        scope.launch {
            mutex.withLock {
                val current = session ?: return@withLock
                if (!current.incomingTerminal || !current.outgoingTerminal) return@withLock
                current.lastActivityAt = nowMillis()
            }
        }
    }

    /** User picked a peer from the nickname chooser. */
    fun choosePeer(key: String) {
        scope.launch {
            mutex.withLock {
                val current = session ?: return@withLock
                current.manualChoiceKey = key
                val candidate = current.candidates.values.firstOrNull { it.nonceKey == key }
                if (candidate != null) selectLocked(current, candidate)
            }
        }
    }

    fun acceptContent() {
        scope.launch {
            mutex.withLock {
                val current = session ?: return@withLock
                // The frozen offer, not the latest one: this must be the release whose
                // version and size the user saw when they tapped Accept.
                val offer = current.acceptedOffer ?: return@withLock
                if (_state.value.contentPhase != MirorLinkContentPhase.Offered) return@withLock
                val staging = runCatching { contentGateway?.beginStaging(offer.tag) }.getOrNull()
                if (staging == null) {
                    current.contentTerminal = true
                    current.contentOffer = null
                    _state.value = _state.value.copy(
                        contentPhase = MirorLinkContentPhase.Failed,
                        contentOffer = null,
                        contentMessage = "Miror could not prepare space for the update.",
                    )
                    // A content-level problem: tell the donor, but do not make it the
                    // session's failure.
                    notifyLocked(current, ContentDeclineMessage)
                    maybeCompleteLocked(current)
                    return@withLock
                }
                _state.value = _state.value.copy(
                    contentPhase = MirorLinkContentPhase.Receiving,
                    contentProgress = 0f,
                )
                current.staging = staging
                current.expectedContentFiles = offer.files
                current.contentBytesExpected = offer.totalSizeBytes ?: 0L
                current.contentBytesReceived = 0L
                current.activeContentFileIndex = null
                current.nextContentFileIndex = 0
                current.nextContentSequence = 0
                current.activeContentFileBytes = 0L
                current.contentStartedAt = nowMillis()
                // Staging is already recorded on the session, so a failed acceptance is
                // reclaimed by the teardown sendLocked performs before returning false.
                if (!sendLocked(current, ContentAcceptMessage)) return@withLock
            }
        }
    }

    fun declineContent() {
        scope.launch {
            mutex.withLock {
                val current = session ?: return@withLock
                // A decline is scoped to this offer. It changes no stored preference and
                // leaves the already-visible collection untouched.
                current.contentTerminal = true
                current.contentOffer = null
                _state.value = _state.value.copy(
                    contentPhase = MirorLinkContentPhase.Declined,
                    contentOffer = null,
                )
                if (!sendLocked(current, ContentDeclineMessage)) return@withLock
                maybeCompleteLocked(current)
            }
        }
    }

    fun cancel() {
        scope.launch {
            // Best-effort courtesy note to the peer, on a short leash. It runs before the
            // shutdown below so a healthy peer still learns the session ended.
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(CANCEL_NOTICE_TIMEOUT_MILLIS) {
                    mutex.withLock {
                        // Notify, not send: this path tears the session down itself, and a
                        // failed courtesy note must not relabel the user's cancel as a
                        // failure.
                        session?.let { notifyLocked(it, CancelMessage("cancelled")) }
                    }
                }
            }
            // Shut the radios down *before* taking the lock. A send stalled on the
            // transport's in-flight semaphore holds the mutex, and shutdown releases those
            // waiters -- so doing this first is what makes cancelling a stuck session
            // actually work rather than queue behind it.
            runCatching { transport.shutdown() }
            mutex.withLock { teardownLocked(MirorLinkPhase.Cancelled, null) }
        }
    }

    /** Ends the radio session but leaves an already-received collection on screen. */
    fun endLink() {
        scope.launch {
            mutex.withLock {
                val remote = _state.value.remote
                val current = session
                if (current != null) notifyLocked(current, CompleteMessage)
                teardownLocked(MirorLinkPhase.Complete, remote)
            }
        }
    }

    /**
     * Last-resort recovery for a coroutine that failed in a way nothing anticipated.
     *
     * Defence in depth, not the mechanism: every send now reports rather than throws, so
     * nothing routine should reach this. It exists because the alternative default -- an
     * uncaught exception on the process scope reaching Android's uncaught-exception handler
     * -- kills the app, and a Link session is never worth that. Ends the session through
     * the ordinary teardown so radios, staging and received photos are released, and keeps
     * an already-received read-only collection on screen.
     */
    internal fun recoverFromUnexpectedFailure() {
        scope.launch {
            runCatching { transport.shutdown() }
            runCatching {
                mutex.withLock {
                    if (session == null) return@withLock
                    failLocked(SEND_FAILURE_MESSAGE)
                }
            }
        }
    }

    fun dismissRemoteCollection() {
        scope.launch {
            mutex.withLock {
                if (session != null) {
                    teardownLocked(MirorLinkPhase.Idle, null)
                } else {
                    _state.value = _state.value.copy(remote = null, phase = MirorLinkPhase.Idle)
                }
            }
        }
    }

    /**
     * Re-resolves the retained raw manifest in place after a content update.
     *
     * Distinct from the Sigil refresh handoff on purpose: that one dismisses the
     * overlay and navigates to card-data options, which is right for a pasted code and
     * wrong here, where the peer just supplied the very content that was missing.
     */
    fun reResolveRemoteCollection() {
        val remote = _state.value.remote ?: return
        scope.launch {
            _state.value = _state.value.copy(isReResolving = true)
            val resolution = runCatching { snapshots.resolve(remote.manifestBytes) }.getOrNull()
            // The viewer may have been dismissed or replaced while catalog resolution ran.
            // Never resurrect an old remote collection after that point.
            if (_state.value.remote !== remote) return@launch
            _state.value = if (resolution is MirorLinkSnapshotResolution.Resolved) {
                _state.value.copy(
                    remote = remote.copy(collection = resolution.collection),
                    isReResolving = false,
                )
            } else {
                _state.value.copy(isReResolving = false)
            }
        }
    }

    // ------------------------------------------------------------------- discovery

    private suspend fun prepareSnapshot(generationAtBuild: Int, entryIds: Set<Long>?) {
        val startedAt = nowMillis()
        val built = runCatching { snapshots.build(entryIds) }.getOrNull()
        val snapshot = built ?: MirorLinkOutgoingSnapshot.EMPTY
        val prepared = mutex.withLock {
            val current = session ?: return
            if (current.generation != generationAtBuild) return
            current.outgoing = snapshot
            current.outgoingReady.complete(snapshot)
            mirorLinkLog(
                "snapshot ready in ${nowMillis() - startedAt}ms " +
                    "(${snapshot.bytes.size} bytes, ${snapshot.chunkCount} frames)",
            )
            endpointInfoLocked(current)?.let { it to current.localNonce }
        } ?: return
        startRadios(generationAtBuild, prepared.first, prepared.second)
    }

    /** Builds the advertisement payload. Pure; must be called under the lock. */
    private fun endpointInfoLocked(current: Session): ByteArray? {
        val outgoing = current.outgoing ?: return null
        val capability = contentGateway?.capability()
        return MirorLinkEndpointInfo(
            protocolMin = MirorLinkProtocol.SUPPORTED_PROTOCOL_MIN,
            protocolMax = MirorLinkProtocol.SUPPORTED_PROTOCOL_MAX,
            nonce = current.localNonce,
            supportsRawManifest = true,
            contentServe = capability?.canServe ?: false,
            contentInstall = capability?.canInstall ?: false,
            hasSnapshot = outgoing.isPresent,
            nickname = nicknameProvider(),
        ).encode()
    }

    /**
     * Brings the radios up.
     *
     * Advertising and discovery are started **concurrently**, and deliberately outside
     * the coordinator lock. Measured on a Pixel 9: `startAdvertising` does not complete
     * until Nearby has stood up Bluetooth Classic, BLE GATT, BLE L2CAP and a LAN socket,
     * which takes about two seconds. Awaiting that before calling `startDiscovery` meant
     * this phone was structurally unable to see anyone for the first ~2s of every
     * session, and holding the lock across it also stalled inbound peer events.
     */
    private suspend fun startRadios(generationAtStart: Int, info: ByteArray, nonce: ByteArray) {
        radioStartupMutex.withLock radioLock@{
            // Snapshot construction and an older startup may both take long enough for
            // this request to be cancelled before it reaches the platform.
            var stillOwned = mutex.withLock { session?.generation == generationAtStart }
            if (!stillOwned) return@radioLock
            if (radioStartupPoisoned) {
                mutex.withLock {
                    if (session?.generation == generationAtStart) {
                        failLocked("Miror Link could not start on this device. Restart Miror and try again.")
                    }
                }
                return@radioLock
            }
            val cooldownRemaining = mutex.withLock { radioCooldownRemainingLocked() }
            if (cooldownRemaining > 0) {
                mirorLinkLog("waiting ${cooldownRemaining}ms for prior radio teardown to settle")
                delay(cooldownRemaining)
                stillOwned = mutex.withLock { session?.generation == generationAtStart }
                if (!stillOwned) return@radioLock
            }
            startRadiosSerial(generationAtStart, info, nonce)
        }
    }

    /** Remaining post-shutdown settling time. Called only while [mutex] is held. */
    private fun radioCooldownRemainingLocked(): Long {
        val teardownAt = lastRadioTeardownAt ?: return 0L
        val elapsed = (nowMillis() - teardownAt).coerceAtLeast(0L)
        return (radioRestartCooldownMillis - elapsed).coerceAtLeast(0L)
    }

    /**
     * Restarts only discovery after a pre-connection selection failure.
     *
     * Advertising is deliberately kept alive until `Connected`, so restarting it here is
     * redundant and expensive: on the API 29 hardware it took almost three seconds during a
     * rapid-restart collision, long enough for the peer's reconciliation window to expire.
     * This still uses the serialized, bounded platform path—only the unnecessary advertising
     * call is omitted.
     */
    private suspend fun resumeDiscovery(generationAtStart: Int) {
        radioStartupMutex.withLock radioLock@{
            val stillOwned = mutex.withLock {
                val active = session
                active?.generation == generationAtStart &&
                    active.connectedEndpoint == null &&
                    active.selected == null
            }
            if (!stillOwned) return@radioLock
            if (radioStartupPoisoned) {
                mutex.withLock {
                    if (session?.generation == generationAtStart) {
                        failLocked("Miror Link could not restart nearby discovery. Restart Miror and try again.")
                    }
                }
                return@radioLock
            }
            val result = runCatching {
                kotlinx.coroutines.withTimeout(radioStartupTimeoutMillis) {
                    transport.startDiscovery()
                }
            }
            if (result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException) {
                radioStartupPoisoned = true
            }
            if (result.isFailure) {
                mutex.withLock {
                    if (session?.generation == generationAtStart) {
                        failLocked("Miror Link could not restart nearby discovery.")
                    }
                }
            } else {
                mirorLinkLog("discovery resumed after selection restart")
            }
        }
    }

    /**
     * Starts one session's radios while [radioStartupMutex] excludes every replacement.
     *
     * This exclusion is load-bearing. Ordinary cancellation and sibling-start failure are
     * cleaned before this mutex is released. If the finite platform deadline is crossed
     * instead, further starts are poisoned for the process and Android may safely repeat
     * endpoint cleanup when the retired Task eventually completes; no replacement can own
     * the shared client then.
     */
    private suspend fun startRadiosSerial(
        generationAtStart: Int,
        info: ByteArray,
        nonce: ByteArray,
    ) {
        val startedAt = nowMillis()
        val result = runCatching {
            kotlinx.coroutines.withTimeout(radioStartupTimeoutMillis) {
                kotlinx.coroutines.coroutineScope {
                    launch { transport.startAdvertising(info) }
                    launch { transport.startDiscovery() }
                }
            }
        }
        if (result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException) {
            // The platform operation may still complete after coroutine cancellation.
            // Prevent a replacement from owning the same client until process recreation;
            // Android's completion callback performs a second cleanup if it arrives late.
            radioStartupPoisoned = true
        }
        if (result.isFailure) {
            mirorLinkLog("radio startup failed or timed out after ${nowMillis() - startedAt}ms")
            mutex.withLock {
                if (session?.generation == generationAtStart) {
                    failLocked("Miror Link could not start on this device.")
                } else {
                    // State ownership and cleanup stay atomic, while radioStartupMutex
                    // prevents the replacement from touching the shared transport yet.
                    runCatching { transport.shutdown() }
                    // The user's earlier teardown may have happened while this platform
                    // Task was still unresolved. Its completion and cleanup are the true
                    // start of Nearby's settling window, so advance the cooldown anchor.
                    lastRadioTeardownAt = nowMillis()
                }
            }
            return
        }
        mirorLinkLog("radios up in ${nowMillis() - startedAt}ms")

        // The radio setup above is deliberately outside the mutex. Confirm the session is
        // still current before adding the optional beacon; cancel/background may have
        // completed while Nearby was starting. A connection callback can also beat the
        // platform task's completion, in which case beaconing is already over.
        val beaconNeeded = mutex.withLock {
            val active = session
            if (active?.generation != generationAtStart) {
                runCatching { transport.shutdown() }
                // This retired startup came up after its session ended. Count the settling
                // window from this final cleanup, not from the earlier Cancel tap.
                lastRadioTeardownAt = nowMillis()
                null
            } else {
                active.connectedEndpoint == null
            }
        }
        if (beaconNeeded == null) return

        // A replacement session can deliberately suppress an older teardown's detached
        // proximity stop so that cleanup cannot clobber its own BLE objects. Reset any
        // inherited scan/beacon here before establishing the replacement's ownership.
        // Holding the platform mutex means a queued teardown or start cannot overtake it.
        val proximityReset = proximitySource?.let { source ->
            proximityPlatformMutex.withLock {
                val owned = mutex.withLock {
                    session?.generation == generationAtStart
                }
                if (owned) {
                    runCatching { source.stopScan() }
                    runCatching { source.stopBeacon() }
                }
                owned
            }
        } ?: true
        if (!proximityReset) {
            mutex.withLock {
                if (session?.generation != generationAtStart) {
                    runCatching { transport.shutdown() }
                }
            }
            return
        }

        // Beaconing starts with the session so a peer that later needs to rank us already
        // has samples. Scanning does not: it only begins if more than one peer appears.
        val source = proximitySource?.takeIf {
            beaconNeeded &&
            runCatching { it.isAvailable() }.getOrDefault(false)
        }
        val stillCurrent = if (source != null) {
            proximityPlatformMutex.withLock {
                val beaconStarted =
                    runCatching { source.startBeacon(MirorLinkBeacon.serviceUuidString(nonce)) }
                        .isSuccess
                // startBeacon is suspendable. Re-check while retaining the platform-operation
                // lock so a queued stop/start cannot pass this stale cleanup.
                val current = mutex.withLock {
                    if (session?.generation != generationAtStart) {
                        runCatching { transport.shutdown() }
                        false
                    } else {
                        // Reset a prior session's unavailable state when BLE is usable again.
                        proximity.setScanningAvailable(beaconStarted)
                        true
                    }
                }
                if (!current) runCatching { source.stopBeacon() }
                current
            }
        } else {
            mutex.withLock {
                if (session?.generation != generationAtStart) {
                    runCatching { transport.shutdown() }
                    false
                } else {
                    proximity.setScanningAvailable(false)
                    true
                }
            }
        }
        if (!stillCurrent) return

        // Reconcile callbacks which arrived before the platform start tasks completed.
        // Selection ends discovery but deliberately keeps advertising/beaconing for the
        // reciprocal caller; connection ends all three. The platform stop methods are
        // idempotent and unconditional so these calls also close a start/stop crossing.
        var stopBeacon = false
        val owned = mutex.withLock {
            val active = session
            if (active?.generation != generationAtStart) {
                runCatching { transport.shutdown() }
                false
            } else {
                if (active.selected != null) runCatching { transport.stopDiscovery() }
                if (active.connectedEndpoint != null) {
                    runCatching { transport.stopAdvertising() }
                    stopBeacon = true
                }
                true
            }
        }
        if (!owned) return
        if (stopBeacon) {
            proximitySource?.let { source ->
                proximityPlatformMutex.withLock {
                    // A connection failure may have restarted selection while this stop
                    // waited for a platform operation. In that case the same session wants
                    // its beacon again, so do not stop the newer owner's work.
                    val stillConnected = mutex.withLock {
                        val active = session
                        active?.generation == generationAtStart &&
                            active.connectedEndpoint != null
                    }
                    if (stillConnected) runCatching { source.stopBeacon() }
                }
            }
        }
    }

    /**
     * The two deadlines that bound a session's life, checked for as long as it lives.
     *
     * Separate from [runDiscovery] because that loop ends at selection, while the phone
     * stays visible until the connection completes -- advertising is deliberately kept up
     * so the higher-nonce side is not stranded. A bound that ended at selection would
     * therefore leave a sole hostile advertiser able to be selected, never connect, and
     * hold the radios open indefinitely.
     *
     * After connection the bound becomes inactivity rather than total duration: a content
     * relay legitimately runs for minutes, but it always makes progress. A peer that stops
     * sending is not slow, it is gone.
     */
    private suspend fun runSessionDeadlines(myGeneration: Int) {
        while (true) {
            delay(DEADLINE_POLL_INTERVAL_MILLIS)
            val done = mutex.withLock {
                val current = session ?: return@withLock true
                if (current.generation != myGeneration) return@withLock true
                val now = nowMillis()

                if (current.connectedEndpoint == null) {
                    if (now - current.discoveryStartedAt < discoveryTimeoutMillis) {
                        return@withLock false
                    }
                    mirorLinkLog("no connection within ${discoveryTimeoutMillis}ms; releasing radios")
                    teardownLocked(MirorLinkPhase.TimedOut, null)
                    return@withLock true
                }

                // Two idle budgets, because "idle" means different things either side of
                // the collection landing.
                //
                // Before it lands, silence is a stalled exchange and should end quickly.
                // After it lands the session is deliberately kept alive so tapping a card
                // can still fetch that card's photo -- and reading a collection for a
                // minute without touching anything is the normal case, not a stall. A
                // single short budget silently stopped photos mid-browse, with the
                // collection still on screen and no way to tell why. Found on hardware.
                val remote = _state.value.remote
                // Keyed on the exchange being *finished*, not on having received
                // something. Both sides serve photos afterwards, and the side whose peer
                // had an empty collection has no `remote` of its own -- keying on that
                // would quietly stop it answering while the other side was still
                // browsing, which is the asymmetric half of the same bug.
                val exchangeDone = current.incomingTerminal && current.outgoingTerminal
                val budget = if (exchangeDone) viewingIdleTimeoutMillis else inactivityTimeoutMillis
                // Absolute wall clock on an accepted content transfer, checked before
                // the idle budget. Deliberately not refreshed by traffic: perfectly
                // formed chunks arriving very slowly are still a peer holding the radios,
                // the staging directory and the screen for as long as it likes.
                if (current.staging != null &&
                    current.contentStartedAt > 0L &&
                    now - current.contentStartedAt >= contentTransferTimeoutMillis
                ) {
                    mirorLinkLog("content transfer exceeded ${contentTransferTimeoutMillis}ms")
                    abandonContentLocked(
                        current,
                        "The update took too long and was not installed.",
                    )
                    maybeCompleteLocked(current)
                    return@withLock false
                }
                if (now - current.lastActivityAt < budget) return@withLock false
                mirorLinkLog("peer went quiet for ${budget}ms; ending session")
                // A collection that already arrived makes this a finished Link rather
                // than a failed one, matching how an ordinary disconnect is treated.
                if (remote != null) {
                    // Said out loud. The collection stays readable but photos can no
                    // longer be fetched, and silently unavailable photos are exactly the
                    // failure this timeout would otherwise introduce.
                    _state.value = _state.value.copy(
                        sessionNotice = "Miror Link ended after " +
                            "${viewingIdleTimeoutMillis / 60_000} minutes of inactivity. " +
                            "The shared collection is still available.",
                    )
                    teardownLocked(MirorLinkPhase.Complete, remote)
                } else {
                    teardownLocked(MirorLinkPhase.TimedOut, null)
                }
                true
            }
            if (done) return
        }
    }

    /**
     * The stability window exists so a second nearby peer has time to appear before an
     * automatic link happens. It resets on candidate churn, but only before selection:
     * once a peer is chosen, reacting to a third phone mid-connection would be worse
     * than ignoring it.
     */
    private suspend fun runDiscovery() {
        val myGeneration = mutex.withLock { session?.generation } ?: return
        while (true) {
            delay(POLL_INTERVAL_MILLIS)
            val done = mutex.withLock {
                val current = session ?: return@withLock true
                if (current.generation != myGeneration) return@withLock true
                if (current.selected != null) return@withLock true

                val now = nowMillis()
                // The exposure deadline lives in runSessionDeadlines, not here. This loop
                // exits the moment a peer is selected, but advertising deliberately stays
                // up until the connection completes -- so a bound enforced here would end
                // while the phone was still visible.
                val settled = now - current.lastCandidateChangeAt >= stabilityWindowMillis
                if (!settled) return@withLock false

                val candidates = current.candidates.values.toList()
                val compatible = MirorLinkSelection.compatible(candidates)
                if (compatible.isEmpty()) return@withLock false

                // Scanning is the expensive part, so it starts only once there is an
                // actual ambiguity to resolve.
                if (compatible.size > 1 && !current.scanning) {
                    current.scanning = true
                    startProximityScan(current, compatible)
                }

                val decision = if (compatible.size == 1) {
                    MirorLinkProximityDecision.Unavailable
                } else {
                    proximity.decide(compatible.map { it.nonceKey }, now)
                }

                when (val outcome = MirorLinkSelection.choose(candidates, decision)) {
                    is MirorLinkSelection.Outcome.Selected -> {
                        selectLocked(current, outcome.candidate)
                        true
                    }

                    is MirorLinkSelection.Outcome.NeedsChooser -> {
                        // Do not sit out the full ambiguity timeout when the answer is
                        // already known to be "ask"; but do give proximity a brief chance
                        // to become confident first.
                        val elapsed = now - current.discoveryStartedAt
                        if (elapsed >= chooserDelayMillis && !current.chooserShown) {
                            current.chooserShown = true
                            _state.value = _state.value.copy(
                                phase = MirorLinkPhase.SelectingPeer,
                                showChooser = true,
                                candidates = MirorLinkSelection.toChoices(outcome.candidates),
                            )
                        } else if (current.chooserShown) {
                            _state.value = _state.value.copy(
                                candidates = MirorLinkSelection.toChoices(outcome.candidates),
                            )
                        }
                        false
                    }

                    MirorLinkSelection.Outcome.None -> false
                }
            }
            if (done) return
        }
    }

    /**
     * Maps a scanned beacon UUID back to the Nearby session that advertised it. The
     * mapping is derived, not transmitted: each candidate's nonce produces exactly one
     * UUID, so a sample can be attributed without any identifier crossing the air.
     */
    private fun startProximityScan(
        current: Session,
        compatible: List<MirorLinkSelection.Candidate>,
    ) {
        val source = proximitySource ?: return
        if (!source.isAvailable()) {
            proximity.setScanningAvailable(false)
            return
        }
        val uuidToNonceKey = compatible.associate {
            MirorLinkBeacon.serviceUuidString(it.info.nonce).lowercase() to it.nonceKey
        }
        val generationAtScan = current.generation
        val scanEpoch = current.proximityScanEpoch
        scope.launch {
            val started = proximityPlatformMutex.withLock {
                val result = runCatching {
                    source.startScan(uuidToNonceKey.keys.toList()) { uuid, rssi ->
                        val nonceKey = uuidToNonceKey[uuid.lowercase()] ?: return@startScan
                        // Android invokes this on its scan callback thread. Channel.trySend
                        // is thread-safe and bounded; the consumer performs both epoch
                        // checks and resolver mutation under the coordinator mutex.
                        proximitySamples.offer(
                            MirorLinkProximitySample(
                                generation = generationAtScan,
                                scanEpoch = scanEpoch,
                                nonceKey = nonceKey,
                                rssiDbm = rssi,
                                atMillis = nowMillis(),
                            ),
                        )
                    }
                }
                val keep = result.isSuccess && mutex.withLock {
                    val active = session
                    active?.generation == generationAtScan &&
                        active.proximityScanEpoch == scanEpoch &&
                        active.scanning &&
                        active.selected == null
                }
                // stopScan may have raced startup before it entered this serialized block.
                // Stop again before another start/stop can pass if the work is now stale.
                if (!keep) runCatching { source.stopScan() }
                result
            }
            if (started.isFailure) {
                mutex.withLock {
                    val active = session
                    if (active?.generation == generationAtScan &&
                        active.proximityScanEpoch == scanEpoch
                    ) {
                        proximity.setScanningAvailable(false)
                    }
                }
            }
        }
    }

    /**
     * The single rule for recording a peer, used by every path that can add one.
     *
     * Returns false when the peer cannot be tracked, which the caller must treat as
     * "ignore this endpoint" rather than proceeding without it. Endpoint ids are
     * peer-supplied and unauthenticated, so every map keyed by one grows on someone
     * else's say-so; a cap enforced on only some of the insertion paths is not a cap.
     * Already-tracked endpoints always update, so a refreshed advertisement is kept.
     */
    private fun trackCandidateLocked(
        current: Session,
        endpointId: String,
        info: MirorLinkEndpointInfo,
    ): Boolean {
        val nonceKey = info.nonce.toHexLowercase()
        if (nonceKey in localSessionNonceKeys) {
            // Play Services can report our own retired advertisement after rapid restart.
            // It is not a peer and must never reach proximity ranking or the chooser.
            return false
        }
        val failure = current.endpointFailures[endpointId]
        // Retire one advertised session, not every future Link session that Nearby happens
        // to expose through the same endpoint id.
        if (failure?.nonceKey == nonceKey && failure.attempts >= MAX_ENDPOINT_ATTEMPTS) {
            return false
        }
        if (failure != null && failure.nonceKey != nonceKey) {
            current.endpointFailures.remove(endpointId)
        }
        // Nonce rotation must not turn the per-identity allowance into unbounded work.
        if (current.totalEndpointFailures >= MAX_TOTAL_ENDPOINT_FAILURES &&
            endpointId !in current.candidates
        ) {
            return false
        }
        if (endpointId !in current.candidates && current.candidates.size >= MAX_TRACKED_PEERS) {
            return false
        }
        current.candidates[endpointId] = MirorLinkSelection.Candidate(endpointId, info)
        return true
    }

    /** Records one ephemeral local identity while keeping rapid restarts strictly bounded. */
    private fun rememberLocalNonceLocked(nonce: ByteArray) {
        val key = nonce.toHexLowercase()
        if (key in localSessionNonceKeys) return
        if (localSessionNonceKeys.size >= MAX_RECENT_LOCAL_NONCES) {
            localSessionNonceKeys.firstOrNull()?.let(localSessionNonceKeys::remove)
        }
        localSessionNonceKeys.add(key)
    }

    /**
     * Counts a failed connection attempt, retiring the endpoint once it runs out of tries.
     *
     * Keeps the map bounded the same way every other peer-keyed structure is: endpoint ids
     * are peer-supplied, so this must not grow on someone else's say-so.
     */
    private fun noteEndpointFailureLocked(
        current: Session,
        candidate: MirorLinkSelection.Candidate,
    ) {
        val endpointId = candidate.endpointId
        val nonceKey = candidate.nonceKey
        val previous = current.endpointFailures[endpointId]
        val attempts = if (previous?.nonceKey == nonceKey) previous.attempts + 1 else 1
        if (endpointId !in current.endpointFailures &&
            current.endpointFailures.size >= MAX_TRACKED_PEERS
        ) {
            current.endpointFailures.keys.firstOrNull()?.let { current.endpointFailures.remove(it) }
        }
        current.endpointFailures[endpointId] = EndpointFailure(nonceKey, attempts)
        current.totalEndpointFailures =
            (current.totalEndpointFailures + 1).coerceAtMost(MAX_TOTAL_ENDPOINT_FAILURES)
        if (attempts >= MAX_ENDPOINT_ATTEMPTS) {
            mirorLinkLog("advertised peer session retired after $attempts failed attempts")
            current.candidates.remove(endpointId)
        }
    }

    /**
     * Adopts fresh endpoint metadata for the peer this session has already selected.
     *
     * `selected` is a *snapshot* of a candidate, and selection stops discovery -- so once a
     * peer is chosen, `PeerFound` can never correct it. If that peer restarts its Link
     * session it re-advertises under a new nonce, and every identity decision this side
     * makes keeps using the retired one: `acceptsInboundFrom` rejects the genuine caller,
     * `shouldInitiate` can have both sides abstain, and `sessionIdFor` derives an id the
     * peer will never address. All three were reproduced on hardware as a 60-90 second
     * stall ending in "No one nearby" while the peer sat two feet away.
     *
     * `ConnectionInitiated` is the only remaining channel carrying fresh metadata, so it is
     * where reconciliation has to happen.
     *
     * Re-deriving the session id is safe **only** before anything has been exchanged under
     * the old one. The guard below is deliberately stricter than "not connected": it also
     * requires that no frame has been sent or accepted, so the new identity cannot inherit
     * a message-id window from the old. With `messageId == 0` and `lastPeerMessageId == -1`
     * this is indistinguishable from having selected the fresh candidate to begin with,
     * which is why it introduces no replay or stale-frame surface. A nonce is never reused:
     * the peer minted a new one, and this side keeps its own.
     */
    private fun refreshSelectedPeerLocked(
        current: Session,
        endpointId: String,
        info: MirorLinkEndpointInfo,
    ) {
        val selected = current.selected ?: return
        if (selected.endpointId != endpointId) return
        if (selected.info.nonce.contentEquals(info.nonce)) return
        if (current.connectedEndpoint != null ||
            current.peerHello != null ||
            current.messageId != 0 ||
            current.lastPeerMessageId != -1
        ) {
            // Something has already been exchanged under the current identity. Changing it
            // now would be a mid-session identity swap, which the protocol does not permit.
            return
        }
        mirorLinkLog("selected peer re-advertised under a new nonce; re-deriving session identity")
        current.selected = MirorLinkSelection.Candidate(endpointId, info)
        current.sessionId = sessionIdFor(current.localNonce, info.nonce)
        // A refusal against the retired advertised identity says nothing about this fresh
        // one, even when Nearby reused the endpoint id.
        current.selectedConnectionAttemptFailed = false
        _state.value = _state.value.copy(
            peerNickname = info.nickname.ifBlank { _state.value.peerNickname ?: "this Miror" },
        )
    }

    /**
     * The outbound half of selection, shared with the initiation fallback.
     *
     * [isFallback] separates the diagnostic only; both failures use the same guarded collision
     * grace. The fallback is speculative by construction -- it fires precisely when a
     * connection may already be in progress -- and Nearby refuses a duplicate request
     * against an in-flight or established endpoint. Immediate failure would let the
     * fallback kill the connection it exists to rescue, while ignoring the failure forever
     * lets two colliding fallbacks strand both phones in `Connecting`. The epoch-guarded
     * grace resolves both: `Connected` wins as a no-op, otherwise selection restarts.
     */
    private suspend fun requestConnectionLocked(
        current: Session,
        candidate: MirorLinkSelection.Candidate,
        isFallback: Boolean,
    ) {
        val outgoing = current.outgoing ?: MirorLinkOutgoingSnapshot.EMPTY
        val capability = contentGateway?.capability()
        val info = MirorLinkEndpointInfo(
            protocolMin = MirorLinkProtocol.SUPPORTED_PROTOCOL_MIN,
            protocolMax = MirorLinkProtocol.SUPPORTED_PROTOCOL_MAX,
            nonce = current.localNonce,
            supportsRawManifest = true,
            contentServe = capability?.canServe ?: false,
            contentInstall = capability?.canInstall ?: false,
            hasSnapshot = outgoing.isPresent,
            nickname = nicknameProvider(),
        ).encode()
        runCatching { transport.requestConnection(candidate.endpointId, info) }
            .onFailure {
                if (current.selected?.endpointId == candidate.endpointId &&
                    current.selected?.nonceKey == candidate.nonceKey
                ) {
                    current.selectedConnectionAttemptFailed = true
                }
                // With simultaneous initiation Nearby can fail this outbound Task while its
                // inbound half is still completing. Resolve after one short guarded window
                // rather than tearing that healthy connection down under it -- or, for two
                // failed fallbacks, waiting forever for a connection that no longer exists.
                val requestKind = if (isFallback) "fallback" else "selected endpoint"
                mirorLinkLog("$requestKind connection request refused; awaiting collision outcome")
                scheduleConnectionFailureResolutionLocked(current, candidate)
            }
    }

    /**
     * Resolves a platform connection failure after an inbound collision has had time to win.
     *
     * Nearby's simultaneous-initiation policy can fail the outbound request and continue the
     * inbound request for the same endpoint. Its callbacks cannot update coordinator state
     * until [requestConnectionLocked] releases [mutex], so acting inside the Task failure
     * callback necessarily races and can disconnect the connection that is about to succeed.
     *
     * One timer per selection epoch keeps hostile/repeated callbacks bounded. A successful
     * `Connected` callback makes it a no-op; otherwise the existing attempt budget retires
     * the endpoint and returns to discovery well inside the global deadline.
     */
    private fun scheduleConnectionFailureResolutionLocked(
        current: Session,
        candidate: MirorLinkSelection.Candidate,
    ) {
        val selectionEpoch = current.selectionEpoch
        if (current.failureResolutionEpoch == selectionEpoch) return
        current.failureResolutionEpoch = selectionEpoch
        val generationAtFailure = current.generation
        scope.launch {
            delay(connectionFailureGraceMillis)
            mutex.withLock {
                val active = session ?: return@withLock
                if (active.generation != generationAtFailure ||
                    active.selectionEpoch != selectionEpoch
                ) {
                    return@withLock
                }
                active.failureResolutionEpoch = null
                if (active.connectedEndpoint != null) return@withLock
                val selected = active.selected ?: return@withLock
                if (selected.endpointId != candidate.endpointId ||
                    selected.nonceKey != candidate.nonceKey
                ) {
                    // The selected endpoint refreshed its advertised identity while this
                    // timer was waiting. A platform failure recorded after that refresh
                    // still needs a collision window of its own; otherwise the retired
                    // identity's timer consumes the epoch's only slot and the replacement
                    // can remain in Connecting until the global discovery deadline.
                    if (active.selectedConnectionAttemptFailed) {
                        scheduleConnectionFailureResolutionLocked(active, selected)
                    }
                    return@withLock
                }
                mirorLinkLog("connection did not recover after collision window; returning to selection")
                noteEndpointFailureLocked(active, selected)
                restartSelectionLocked(active)
            }
        }
    }

    /** Accepts one verified Nearby request and records exactly which identity was admitted. */
    private suspend fun acceptConnectionLocked(current: Session, endpointId: String) {
        val candidate = current.candidates[endpointId]
            ?: current.selected?.takeIf { it.endpointId == endpointId }
            ?: return
        val accepted = runCatching { transport.acceptConnection(endpointId) }.isSuccess
        if (!accepted) return
        if (endpointId !in current.admittedEndpointNonces &&
            current.admittedEndpointNonces.size >= MAX_TRACKED_PEERS
        ) {
            current.admittedEndpointNonces.keys.firstOrNull()?.let {
                current.admittedEndpointNonces.remove(it)
            }
        }
        current.admittedEndpointNonces[endpointId] = candidate.nonceKey
    }

    /**
     * Breaks a mutual abstention.
     *
     * The initiator election is deterministic only while both sides agree on both nonces.
     * When one side is working from retired metadata they can *both* conclude they are the
     * higher nonce and wait, and nothing else in the protocol ever breaks that tie -- the
     * pair simply idles until the 90 second discovery deadline.
     *
     * So after a short wait with a peer selected and no connection, this side calls anyway.
     * Nearby's simultaneous-initiation case is already handled: a duplicate request against
     * an in-flight or established connection fails non-fatally and is caught, and
     * `acceptsInboundFrom` resolves who accepts. Fires at most once per selection.
     */
    private suspend fun runInitiationFallback(
        generationAtSelect: Int,
        selectionEpochAtStart: Int,
    ) {
        delay(initiationFallbackMillis)
        mutex.withLock {
            val current = session ?: return@withLock
            if (current.generation != generationAtSelect) return@withLock
            if (current.selectionEpoch != selectionEpochAtStart) return@withLock
            if (current.connectedEndpoint != null) return@withLock
            if (current.initiationFallbackUsed) return@withLock
            val candidate = current.selected ?: return@withLock
            current.initiationFallbackUsed = true
            mirorLinkLog("selection did not reach a connection; initiating from this side")
            requestConnectionLocked(current, candidate, isFallback = true)
        }
    }

    private suspend fun selectLocked(current: Session, candidate: MirorLinkSelection.Candidate) {
        if (current.selected != null) return
        current.selectionEpoch++
        current.failureResolutionEpoch = null
        current.selectedConnectionAttemptFailed = false
        current.selected = candidate
        current.sessionId = sessionIdFor(current.localNonce, candidate.info.nonce)

        _state.value = _state.value.copy(
            phase = MirorLinkPhase.Connecting,
            showChooser = false,
            candidates = emptyList(),
            peerNickname = candidate.info.nickname.ifBlank { "this Miror" },
        )

        // Stop discovering immediately: continuing to scan destabilises connection setup.
        // Advertising and the beacon stay up until the peer's reciprocal selection is
        // evidenced by connection initiation/acceptance.
        runCatching { transport.stopDiscovery() }
        if (current.scanning) {
            current.scanning = false
            proximitySource?.let { source ->
                scope.launch {
                    proximityPlatformMutex.withLock { runCatching { source.stopScan() } }
                }
            }
        }

        // Resolve any inbound request that arrived while selection was still settling.
        val held = current.pendingInbound.keys.toList()
        held.forEach { endpointId ->
            val info = current.candidates[endpointId]?.info
            when (MirorLinkSelection.acceptsInboundFrom(candidate.info.nonce, info?.nonce)) {
                MirorLinkSelection.InboundDecision.Accept -> {
                    current.pendingInbound.remove(endpointId)
                    acceptConnectionLocked(current, endpointId)
                }
                MirorLinkSelection.InboundDecision.Reject -> {
                    current.pendingInbound.remove(endpointId)
                    runCatching { transport.rejectConnection(endpointId) }
                }
                MirorLinkSelection.InboundDecision.Hold -> Unit
            }
        }

        if (MirorLinkSelection.shouldInitiate(current.localNonce, candidate.info.nonce)) {
            requestConnectionLocked(current, candidate, isFallback = false)
        }

        // Bounded route to a connection whichever way the election went, including the case
        // where the two sides elected from inconsistent metadata and both abstained.
        val generationAtSelect = current.generation
        val selectionEpochAtStart = current.selectionEpoch
        scope.launch { runInitiationFallback(generationAtSelect, selectionEpochAtStart) }
    }

    // -------------------------------------------------------------------- transport

    private fun observeTransport() {
        eventJob?.cancel()
        eventJob = scope.launch {
            transport.events.collect { event ->
                try {
                    handleTransportEvent(event)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Transport/decoder exception messages can contain SDK endpoint ids
                    // or peer-controlled values. Release logs need the outcome, not the
                    // identity or contents of the collection involved.
                    mirorLinkLog("transport event failed")
                    mutex.withLock {
                        if (session != null) {
                            failLocked("Miror Link lost its connection. Try again.")
                        }
                    }
                }
            }
        }
    }

    private fun observeLifecycle() {
        lifecycleJob?.cancel()
        lifecycleJob = scope.launch {
            lifecycle.events.collect { event ->
                if (event == MirorLinkLifecycleEvent.Backgrounded ||
                    event == MirorLinkLifecycleEvent.Interrupted
                ) {
                    // Radios first, lock second -- leaving the foreground must not queue
                    // behind a send that is stalled on an unresponsive peer.
                    runCatching { transport.shutdown() }
                    mutex.withLock {
                        // A pre-warm has no session to tear down, but it does have live
                        // discovery and a buffer of peers. Foreground-only applies to it
                        // for exactly the same reason it applies to a session.
                        if (prewarming) {
                            prewarming = false
                            prewarmDiscovered.clear()
                            eventJob?.cancel()
                            eventJob = null
                        }
                        // Foreground-only by product decision: leaving the foreground stops
                        // discovery, advertising, BLE work, pending connections and any
                        // in-flight collection transfer. Returning requires restarting Link.
                        val remote = _state.value.remote
                        if (session != null) teardownLocked(MirorLinkPhase.Cancelled, remote)
                    }
                }
            }
        }
    }

    private suspend fun handleTransportEvent(event: MirorLinkTransportEvent) = mutex.withLock {
        val current = session
        if (current == null) {
            // A stale initiation callback still owns a platform verification request.
            // Ignoring it would leave Nearby waiting after the Miror session has ended
            // (or while discovery is only pre-warmed), so always answer it explicitly.
            if (event is MirorLinkTransportEvent.ConnectionInitiated) {
                runCatching { transport.rejectConnection(event.endpointId) }
                return@withLock
            }
            // Pre-warm: no session to drive yet, but remember who is out there so the
            // session that follows does not have to wait for a callback Nearby will never
            // send again.
            if (prewarming) {
                when (event) {
                    is MirorLinkTransportEvent.PeerFound ->
                        if (event.info.nonce.toHexLowercase() !in localSessionNonceKeys &&
                            (prewarmDiscovered.size < MAX_TRACKED_PEERS ||
                                event.endpointId in prewarmDiscovered)
                        ) {
                            prewarmDiscovered[event.endpointId] = event.info
                        }
                    is MirorLinkTransportEvent.PeerLost ->
                        prewarmDiscovered.remove(event.endpointId)
                    else -> Unit
                }
            }
            return@withLock
        }
        when (event) {
            is MirorLinkTransportEvent.PeerFound -> {
                if (current.selected != null) return@withLock
                if (!trackCandidateLocked(current, event.endpointId, event.info)) return@withLock
                current.lastCandidateChangeAt = nowMillis()
                _state.value = _state.value.copy(phase = MirorLinkPhase.Discovering)
            }

            is MirorLinkTransportEvent.PeerLost -> {
                // Discovery callbacks can be delivered after a connection is already
                // established. The connection callback is authoritative from this point;
                // a stale PeerLost must not dismantle a healthy exchange.
                if (current.connectedEndpoint == event.endpointId) {
                    return@withLock
                }
                if (current.selected?.endpointId == event.endpointId) {
                    restartSelectionLocked(current)
                } else if (current.candidates.remove(event.endpointId) != null) {
                    current.lastCandidateChangeAt = nowMillis()
                }
            }

            is MirorLinkTransportEvent.ConnectionInitiated -> {
                // Same capped rule as discovery. This path used to insert with getOrPut
                // *before* any limit applied, so an inbound peer could grow the map past
                // the cap that discovery respected -- the bound held on one door only.
                val info = event.info
                if (info != null && !trackCandidateLocked(current, event.endpointId, info)) {
                    runCatching { transport.rejectConnection(event.endpointId) }
                    return@withLock
                }
                val selectedBeforeRefresh = current.selected
                if (event.isIncoming &&
                    info != null &&
                    selectedBeforeRefresh != null &&
                    selectedBeforeRefresh.endpointId != event.endpointId &&
                    current.connectedEndpoint == null &&
                    current.peerHello == null &&
                    current.messageId == 0 &&
                    current.lastPeerMessageId == -1 &&
                    current.selectedConnectionAttemptFailed &&
                    info.nickname == selectedBeforeRefresh.info.nickname
                ) {
                    /*
                     * A rapid Link restart may replace both the nonce and Nearby endpoint
                     * id. The waiting phone stopped discovery when it selected the retired
                     * advertisement, so this inbound request is the first fresh metadata it
                     * can receive. Rerun the ordinary selection policy with the caller
                     * seeded, but keep its verification request pending: rejecting first
                     * tears down the only healthy route before selection can choose it.
                     *
                     * A different endpoint is not enough by itself. An unrelated third
                     * phone can call during Connecting, so this branch is gated by an actual
                     * platform failure against the selected endpoint and the same sanitized
                     * user nickname. Without both continuity signals the ordinary inbound
                     * rule below rejects the unselected caller and preserves the user's
                     * existing choice. Nicknames are not authentication (impersonation is an
                     * accepted residual), but they prevent an honest third phone with a
                     * different identity from being mistaken for the restart.
                     *
                     * This is not immediate adoption. The caller still has to win ordinary
                     * one-candidate selection, and any other candidate delivered before the
                     * stability decision restores the normal proximity/chooser ambiguity.
                     * The bounded inbound timer rejects whichever request did not win.
                     */
                    mirorLinkLog("new endpoint called before HELLO; reconciling peer selection")
                    noteEndpointFailureLocked(current, selectedBeforeRefresh)
                    restartSelectionLocked(
                        current,
                        seedCandidate = MirorLinkSelection.Candidate(event.endpointId, info),
                    )
                    holdInboundLocked(current, event.endpointId)
                    return@withLock
                }
                // Reconcile before the decision below reads the selected peer's nonce.
                // Tracking the candidate is not enough on its own: `selected` is a separate
                // snapshot, and it is the one every identity rule actually consults.
                if (info != null) refreshSelectedPeerLocked(current, event.endpointId, info)
                val requesterNonce = info?.nonce
                    ?: current.candidates[event.endpointId]?.info?.nonce
                when (
                    MirorLinkSelection.acceptsInboundFrom(current.selected?.info?.nonce, requesterNonce)
                ) {
                    MirorLinkSelection.InboundDecision.Accept ->
                        acceptConnectionLocked(current, event.endpointId)

                    MirorLinkSelection.InboundDecision.Reject ->
                        runCatching { transport.rejectConnection(event.endpointId) }

                    MirorLinkSelection.InboundDecision.Hold -> {
                        // First request owns the one bounded decision timer. Repeated
                        // callbacks for the same endpoint must not mint sleeping coroutines.
                        holdInboundLocked(current, event.endpointId)
                    }
                }
            }

            is MirorLinkTransportEvent.Connected -> {
                var selectedEndpoint = current.selected?.endpointId
                if (selectedEndpoint == null &&
                    current.connectedEndpoint == null &&
                    current.peerHello == null &&
                    current.messageId == 0 &&
                    current.lastPeerMessageId == -1
                ) {
                    /*
                     * A request we already accepted can complete just after a racing
                     * ConnectionFailed returned us to selection. Nearby has now proved that
                     * both phones accepted this exact endpoint, but `selected` was cleared
                     * while that platform operation was in flight. If its capped candidate
                     * metadata survived/re-arrived, restore only that admitted identity.
                     *
                     * This is deliberately not "accept any Connected callback": a callback
                     * for an unknown endpoint, one competing with a different selection, or
                     * one arriving after any protocol traffic is still disconnected below.
                     * A stale nonce cannot silently corrupt the exchange either -- HELLO's
                     * session binding will reject it immediately.
                     */
                    current.candidates[event.endpointId]
                        ?.takeIf {
                            current.admittedEndpointNonces[event.endpointId] == it.nonceKey
                        }
                        ?.let { candidate ->
                            current.selected = candidate
                            current.sessionId =
                                sessionIdFor(current.localNonce, candidate.info.nonce)
                            current.selectionEpoch++
                            selectedEndpoint = candidate.endpointId
                            _state.value = _state.value.copy(
                                phase = MirorLinkPhase.Connecting,
                                peerNickname = candidate.info.nickname,
                                showChooser = false,
                                candidates = emptyList(),
                            )
                            runCatching { transport.stopDiscovery() }
                        }
                }
                if (selectedEndpoint == null || event.endpointId != selectedEndpoint) {
                    // A stale or adversarial callback must not replace the peer that the
                    // user/selection algorithm chose. Endpoint ids are ephemeral, but
                    // they are still the transport binding for this one session.
                    runCatching { transport.disconnect(event.endpointId) }
                    return@withLock
                }
                // Nearby may repeat a terminal callback. Do not resend HELLO or let a
                // duplicate callback extend the session's inactivity budget.
                if (current.connectedEndpoint != null) return@withLock
                current.connectedEndpoint = event.endpointId
                current.lastActivityAt = nowMillis()
                // Reciprocal selection is now evidenced, so advertising and the beacon can
                // both stop. Stopping either earlier can strand the higher-nonce side.
                runCatching { transport.stopAdvertising() }
                proximitySource?.let { source ->
                    scope.launch {
                        proximityPlatformMutex.withLock { runCatching { source.stopBeacon() } }
                    }
                }
                _state.value = _state.value.copy(phase = MirorLinkPhase.ExchangingHello)
                if (!sendHelloLocked(current)) return@withLock
            }

            is MirorLinkTransportEvent.ConnectionFailed -> {
                // A duplicate/speculative request may report failure after another request
                // has already connected. Once Connected wins, later failure callbacks for
                // that endpoint are stale and cannot revoke it.
                if (current.connectedEndpoint == event.endpointId) {
                    return@withLock
                }
                val selected = current.selected
                if (selected?.endpointId == event.endpointId) {
                    current.selectedConnectionAttemptFailed = true
                    // The callback may describe the losing outbound half of a simultaneous
                    // initiation while the inbound half is still completing.
                    scheduleConnectionFailureResolutionLocked(current, selected)
                }
            }

            is MirorLinkTransportEvent.Disconnected -> {
                if (current.connectedEndpoint == event.endpointId) {
                    val remote = _state.value.remote
                    if (remote != null) {
                        // The collection already landed. Losing the radio afterwards is a
                        // completed Link, not a failure.
                        teardownLocked(MirorLinkPhase.Complete, remote)
                    } else {
                        failLocked("The other phone disconnected before the collection arrived.")
                    }
                }
            }

            is MirorLinkTransportEvent.FrameReceived ->
                handleFrameLocked(current, event.endpointId, event.frame)

            is MirorLinkTransportEvent.TransportFailure -> {
                // Platform/SDK exception text is not product copy and may contain
                // endpoint identifiers or other implementation details. Keep peer and
                // system-controlled strings out of the user-facing failure surface.
                if (event.fatal) {
                    failLocked("Miror Link could not start nearby sharing. Check your radios and try again.")
                }
            }
        }
    }

    private suspend fun resolveHeldInbound(
        generationAtRequest: Int,
        endpointId: String,
        requestToken: Long,
    ) {
        delay(inboundHoldMillis)
        mutex.withLock {
            val current = session ?: return@withLock
            if (current.generation != generationAtRequest) return@withLock
            if (current.pendingInbound[endpointId] != requestToken) return@withLock
            current.pendingInbound.remove(endpointId)
            val requesterNonce = current.candidates[endpointId]?.info?.nonce
            when (
                MirorLinkSelection.acceptsInboundFrom(current.selected?.info?.nonce, requesterNonce)
            ) {
                MirorLinkSelection.InboundDecision.Accept ->
                    acceptConnectionLocked(current, endpointId)
                // Rejecting on timeout must not strand the requester: it gets an explicit
                // rejection callback and starts a fresh selection generation rather than
                // waiting forever.
                else -> runCatching { transport.rejectConnection(endpointId) }
            }
        }
    }

    /**
     * Keeps one platform verification request alive while ordinary selection settles.
     *
     * Nearby permits a short asynchronous verification window. A rapid peer restart can
     * first surface its fresh endpoint through this callback after discovery stopped on the
     * retired endpoint. Rejecting it immediately makes the caller tear down the only healthy
     * route between the phones. Holding it does not bypass selection: [selectLocked] accepts
     * it only if the same candidate wins, and [resolveHeldInbound] rejects it on the existing
     * bounded deadline if ambiguity or another peer wins.
     */
    private fun holdInboundLocked(current: Session, endpointId: String) {
        if (endpointId in current.pendingInbound) return
        if (current.pendingInbound.size >= MAX_TRACKED_PEERS) {
            scope.launch { runCatching { transport.rejectConnection(endpointId) } }
            return
        }
        current.nextInboundToken++
        val requestToken = current.nextInboundToken
        current.pendingInbound[endpointId] = requestToken
        scope.launch {
            resolveHeldInbound(
                current.generation,
                endpointId,
                requestToken,
            )
        }
    }

    private suspend fun restartSelectionLocked(
        current: Session,
        seedCandidate: MirorLinkSelection.Candidate? = null,
    ) {
        current.selected = null
        current.sessionId = null
        current.connectedEndpoint = null
        current.peerHello = null
        current.lastPeerMessageId = -1
        current.helloSent = false
        current.helloIdentityReconciled = false
        current.staleHelloResponseSent = false
        current.staleHelloSessionId = null
        current.lastStaleHelloMessageId = -1
        current.selectedConnectionAttemptFailed = false
        current.snapshotSent = false
        current.assembler = null
        current.peerOffer = null
        current.outgoingContentOffered = false
        current.outgoingContentDescriptor = null
        // The endpoint this stream was addressed to is gone; a fresh acceptance clears the
        // abort flag before starting the next one.
        stopOutgoingContentLocked(current)
        current.candidates.clear()
        if (seedCandidate != null) {
            current.candidates[seedCandidate.endpointId] = seedCandidate
        }
        current.pendingInbound.clear()
        current.lastCandidateChangeAt = nowMillis()
        // A fresh selection gets a fresh fallback; the previous one is already spent or
        // invalidated by the checks it performs.
        current.initiationFallbackUsed = false
        current.failureResolutionEpoch = null
        current.proximityScanEpoch++
        proximity.clear()
        proximitySamples.discardPending()
        _state.value = _state.value.copy(
            phase = MirorLinkPhase.Discovering,
            peerNickname = null,
            showChooser = false,
            candidates = emptyList(),
        )
        // Selection stopped discovery, but advertising deliberately stays alive until a
        // connection succeeds. Resume only discovery; restarting both platform operations
        // adds seconds of churn and can outlive the peer's collision-recovery window.
        val generationAtRestart = current.generation
        if (current.outgoing != null) {
            scope.launch { resumeDiscovery(generationAtRestart) }
        } else {
            // Selection can only happen after snapshot preparation populated `outgoing`,
            // so this is an internal invariant failure. Do not bypass the serialized,
            // bounded discovery path.
            failLocked("Miror Link could not restart nearby sharing.")
            return
        }
        scope.launch { runDiscovery() }
    }

    // --------------------------------------------------------------------- protocol

    /**
     * Encodes and sends one frame, reporting success rather than throwing.
     *
     * Three things can go wrong here and all three used to escape as exceptions:
     *
     * - **Encoding.** `encodeFrame` asserts its own budgets, so a message that does not
     *   fit throws rather than returning.
     * - **The deadline.** The Android transport bounds in-flight payloads with a
     *   semaphore, so a peer that stops draining transfers makes the next send suspend.
     *   That suspension happens under the state mutex, which would also block `cancel()`,
     *   backgrounding and teardown -- so it is bounded, and the bound has to mean
     *   something. Nothing legitimate takes this long: a frame is at most 24 KiB over a
     *   link that has already completed its handshake.
     * - **The transport itself.** Nearby raises on `sendPayload` when the endpoint has
     *   gone away, which is what an ordinary walk-out-of-range looks like from here.
     *
     * Most callers run in coroutines launched directly on the process scope, where an
     * escaping exception reaches Android's uncaught-exception handler and kills the app.
     * Reporting instead of throwing is what makes the failure a session outcome. See
     * [sendLocked], which is the version every protocol caller should use.
     */
    private suspend fun encodeAndSendLocked(
        current: Session,
        message: MirorLinkMessage,
    ): Boolean {
        val endpointId = current.connectedEndpoint ?: return false
        val sessionId = current.sessionId ?: return false
        val frame = try {
            MirorLinkCodec.encode(sessionId, current.nextMessageId(), message)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Message type only. Encoder text can name a card id, and a card id is
            // peer-influenced.
            mirorLinkLog("could not encode a Link frame of type ${message.type}")
            return false
        }
        val sent = try {
            kotlinx.coroutines.withTimeoutOrNull(sendTimeoutMillis) {
                transport.send(endpointId, frame)
                true
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Structured cancellation must keep propagating: teardown, backgrounding and
            // scope shutdown all rely on it, and swallowing it here would strand them.
            throw cancelled
        } catch (_: Throwable) {
            // Deliberately not the platform's message: SDK exception text carries endpoint
            // identifiers and other implementation detail that is not product copy.
            mirorLinkLog("send failed for a frame of type ${message.type}")
            return false
        }
        if (sent == null) {
            mirorLinkLog("send stalled for ${sendTimeoutMillis}ms")
            return false
        }
        current.lastActivityAt = nowMillis()
        return true
    }

    /**
     * The one send every protocol path uses.
     *
     * Returns false when the frame did not go out, having **already failed and torn down
     * the session** through [failLocked]. A false return therefore means [current] is no
     * longer the live session, so every caller must return immediately rather than keep
     * mutating it -- which is why this reports a Boolean instead of swallowing the failure
     * silently. Continuing would advance sequence numbers, staging state and progress on a
     * session that no longer exists.
     */
    private suspend fun sendLocked(current: Session, message: MirorLinkMessage): Boolean {
        if (encodeAndSendLocked(current, message)) return true
        failLocked(SEND_FAILURE_MESSAGE)
        return false
    }

    /**
     * Best-effort frame on a path that is already ending, or whose failure is not the
     * session's failure. Never escalates.
     *
     * Two callers need this. `cancel()` and `endLink()` send a courtesy notice and then
     * tear the session down themselves -- escalating there would replace the user's
     * Cancelled or Complete with a Failed they did not cause. And a content abort tells
     * the donor to stop, which must not turn a cosmetic content problem into the end of an
     * otherwise finished collection exchange.
     */
    private suspend fun notifyLocked(current: Session, message: MirorLinkMessage) {
        encodeAndSendLocked(current, message)
    }

    private suspend fun sendHelloLocked(current: Session, force: Boolean = false): Boolean {
        if (current.helloSent && !force) return true
        val selected = current.selected ?: return false
        val outgoing = current.outgoing ?: return false
        val capability = contentGateway?.capability()
        if (!force) current.helloSent = true
        // Nothing follows, so there is no stale state to guard against; a failed HELLO has
        // already ended the session by the time this returns.
        return sendLocked(
            current,
            HelloMessage(
                localNonce = current.localNonce,
                selectedPeerNonce = selected.info.nonce,
                protocolMin = MirorLinkProtocol.SUPPORTED_PROTOCOL_MIN,
                protocolMax = MirorLinkProtocol.SUPPORTED_PROTOCOL_MAX,
                nickname = nicknameProvider(),
                appBuild = appBuild,
                snapshotScope = outgoing.scope,
                supportsRawManifest = true,
                contentVersion = capability?.activeVersion,
                contentServe = capability?.canServe ?: false,
                contentInstall = capability?.canInstall ?: false,
                photoServe = photoCodec != null && sharePhotos(),
            ),
        )
    }

    /**
     * Repairs the one recoverable form of a pre-HELLO session-id mismatch.
     *
     * During a rapid cancel/restart, Nearby can connect a waiting phone using the nonce from
     * the retired advertisement. The replacement's HELLO is nevertheless self-describing:
     * it names the fresh peer nonce, the local nonce it selected, and is wrapped in the
     * session id derived from exactly those two values. When it targets this live local
     * nonce, that is enough to replace the stale selected-peer snapshot before any collection
     * data or accepted message-id state exists.
     *
     * This is deliberately not a general session-id bypass:
     *
     * - only a structurally valid HELLO from the already-connected endpoint is considered;
     * - its envelope must agree with the two nonces in its payload;
     * - it must name this live local nonce;
     * - no peer frame or snapshot work may have been accepted;
     * - one session may reconcile at most once.
     *
     * A valid HELLO addressed to a retired local nonce is ignored, with one bounded
     * retransmission of the current HELLO. That lets the other end perform the same repair
     * without allowing stale traffic to hold the inactivity budget open indefinitely.
     */
    private suspend fun reconcilePreHelloMismatchLocked(
        current: Session,
        endpointId: String,
        parsed: MirorLinkFrame,
    ): HelloMessage? {
        if (parsed.type != MirorLinkProtocol.TYPE_HELLO) {
            failLocked(SESSION_CHANGED_MESSAGE)
            return null
        }
        val hello = MirorLinkCodec.decode(parsed) as? HelloMessage ?: run {
            failLocked(SESSION_CHANGED_MESSAGE)
            return null
        }
        val payloadSessionId = sessionIdFor(hello.localNonce, hello.selectedPeerNonce)
        if (!parsed.sessionId.contentEquals(payloadSessionId)) {
            failLocked(SESSION_CHANGED_MESSAGE)
            return null
        }

        if (!hello.selectedPeerNonce.contentEquals(current.localNonce)) {
            // This is a well-formed HELLO for the local session that was just retired. It
            // cannot be accepted here, but one current HELLO gives the peer fresh metadata
            // even when the platform delivered the retired frame first.
            val trackedStaleSession = current.staleHelloSessionId
            if (trackedStaleSession == null) {
                current.staleHelloSessionId = parsed.sessionId.copyOf()
                current.lastStaleHelloMessageId = parsed.messageId
            } else if (trackedStaleSession.contentEquals(parsed.sessionId)) {
                current.lastStaleHelloMessageId =
                    maxOf(current.lastStaleHelloMessageId, parsed.messageId)
            }
            if (!current.staleHelloResponseSent) {
                current.staleHelloResponseSent = true
                if (!sendHelloLocked(current, force = true)) return null
            }
            return null
        }

        val selected = current.selected
        if (selected == null ||
            selected.endpointId != endpointId ||
            current.peerHello != null ||
            current.lastPeerMessageId != -1 ||
            current.snapshotSent ||
            current.helloIdentityReconciled ||
            current.messageId > 2
        ) {
            failLocked(SESSION_CHANGED_MESSAGE)
            return null
        }
        when (MirorLinkSelection.validateHello(hello, current.localNonce, hello.localNonce)) {
            MirorLinkSelection.HelloValidation.Ok -> Unit
            MirorLinkSelection.HelloValidation.IncompatibleProtocol -> {
                updateRequiredLocked("Update Miror to link with this version.")
                return null
            }
            else -> {
                failLocked(SESSION_CHANGED_MESSAGE)
                return null
            }
        }

        val freshInfo = selected.info.copy(
            protocolMin = hello.protocolMin,
            protocolMax = hello.protocolMax,
            nonce = hello.localNonce,
            supportsRawManifest = hello.supportsRawManifest,
            contentServe = hello.contentServe,
            contentInstall = hello.contentInstall,
            hasSnapshot = hello.snapshotScope != MirorLinkSnapshotScope.NONE,
            nickname = hello.nickname,
        )
        val freshCandidate = MirorLinkSelection.Candidate(endpointId, freshInfo)
        current.selected = freshCandidate
        current.candidates[endpointId] = freshCandidate
        current.admittedEndpointNonces[endpointId] = freshCandidate.nonceKey
        current.sessionId = payloadSessionId
        current.messageId = 0
        current.helloSent = false
        current.helloIdentityReconciled = true
        mirorLinkLog("reconciled a restarted peer from its first HELLO")

        // This corrected HELLO is ordered ahead of every snapshot frame launched after the
        // incoming HELLO is accepted below.
        if (!sendHelloLocked(current)) return null
        return hello
    }

    private suspend fun handleFrameLocked(current: Session, endpointId: String, frame: ByteArray) {
        if (endpointId != current.connectedEndpoint) return
        val parsed: MirorLinkFrame
        val decoded = try {
            parsed = MirorLinkProtocol.decodeFrame(frame)
            val expectedSession = current.sessionId
            var reconciledHello: HelloMessage? = null
            // Frames from another session, or after this session ended, are ignored
            // deterministically rather than half-applied.
            if (expectedSession == null || !parsed.sessionId.contentEquals(expectedSession)) {
                if (current.peerHello != null) return
                val trackedStaleSession = current.staleHelloSessionId
                if (trackedStaleSession != null &&
                    trackedStaleSession.contentEquals(parsed.sessionId) &&
                    parsed.messageId <= current.lastStaleHelloMessageId
                ) {
                    // This sequence belongs to the retired session rather than the live
                    // session's replay window. Consume it in its own bounded window so an
                    // exact replay is discarded before HELLO field decoding and hashing.
                    return
                }
                reconciledHello =
                    reconcilePreHelloMismatchLocked(current, endpointId, parsed) ?: return
            }
            // Nearby byte payloads are ordered. Enforcing the sender's monotonically
            // increasing id makes duplicate/replayed and stale frames deterministic no-ops.
            //
            // Activity is deliberately *not* refreshed above this point. Counting bytes
            // rather than progress would let a peer replay one old valid frame on a timer
            // and hold the session open forever -- the inactivity deadline would keep
            // resetting on traffic that changes nothing.
            if (parsed.messageId <= current.lastPeerMessageId) return
            val message = reconciledHello ?: MirorLinkCodec.decode(parsed)
            // Unknown optional messages still consume their sequence number. Otherwise a
            // replay of that frame could be reconsidered indefinitely.
            current.lastPeerMessageId = parsed.messageId
            // Deliberately no activity refresh here. A fresh message id proves the frame
            // is not a replay; it does not prove anything happened. Unknown optional
            // frames and state-valid no-ops consume their id and change nothing, so
            // counting them would let a peer hold the session open with traffic that
            // advances no state. Each handler below refreshes only when it accepts
            // something.
            message ?: return
        } catch (unsupported: MirorLinkUnsupportedException) {
            updateRequiredLocked(unsupported.message ?: "Update Miror to link with this version.")
            return
        } catch (_: MirorLinkProtocolException) {
            failLocked("The other phone sent something Miror could not read.")
            return
        }
        if (current.peerHello == null && decoded !is HelloMessage) {
            failLocked("The other phone sent Link data before pairing completed.")
            return
        }

        when (decoded) {
            is HelloMessage -> handleHelloLocked(current, decoded)
            is SnapshotOfferMessage -> handleSnapshotOfferLocked(current, decoded)
            is SnapshotChunkMessage -> handleSnapshotChunkLocked(current, decoded)
            is SnapshotResultMessage -> {
                // Only the transition counts. Repeats change nothing.
                if (!current.outgoingTerminal) {
                    current.outgoingTerminal = true
                    markProgressLocked(current)
                }
                maybeCompleteLocked(current)
            }
            is ContentOfferMessage -> handleContentOfferLocked(current, decoded)
            ContentAcceptMessage -> {
                if (current.outgoingContentOffered && current.outgoingContent == null) {
                    // Consume the acceptance before launching so a replay or duplicate
                    // cannot open two snapshots and stream them concurrently.
                    current.outgoingContentOffered = false
                    val acceptedOffer = current.outgoingContentDescriptor
                    current.outgoingContentDescriptor = null
                    if (acceptedOffer != null) {
                        markProgressLocked(current)
                        // Cleared for this stream, so an abort recorded against an earlier
                        // one cannot stop the transfer the peer just agreed to.
                        current.outgoingContentAborted = false
                        current.outgoingContentJob =
                            scope.launch { streamContent(current.generation, acceptedOffer) }
                    }
                }
            }
            ContentDeclineMessage -> {
                // A decline answers an offer this side made. Out-of-direction declines
                // are state-valid noise and must not terminate an unrelated incoming
                // transfer or refresh the inactivity budget.
                if (current.outgoingContentOffered ||
                    current.outgoingContentDescriptor != null ||
                    current.outgoingContent != null
                ) {
                    markProgressLocked(current)
                    current.outgoingContentOffered = false
                    current.outgoingContentDescriptor = null
                    stopOutgoingContentLocked(current)
                    maybeCompleteLocked(current)
                }
            }
            is ContentFileBeginMessage -> handleContentFileBeginLocked(current, decoded)
            is ContentChunkMessage -> handleContentChunkLocked(current, decoded)
            is ContentFileEndMessage -> handleContentFileEndLocked(current, decoded)
            is ContentResultMessage -> {
                if (!current.contentTerminal) markProgressLocked(current)
                // Donor side. A result means the receiver has reached a verdict, so there
                // is nothing left to send it -- including when the verdict is a rejection
                // partway through. This is what stops a package being read off disk and
                // pushed at a peer that is already discarding every chunk.
                current.outgoingContentOffered = false
                current.outgoingContentDescriptor = null
                stopOutgoingContentLocked(current)
                if (current.staging != null &&
                    (_state.value.contentPhase == MirorLinkContentPhase.Receiving ||
                        _state.value.contentPhase == MirorLinkContentPhase.Applying)
                ) {
                    abandonContentLocked(
                        current,
                        "The other phone could not send the update.",
                    )
                } else {
                    current.contentTerminal = true
                }
                maybeCompleteLocked(current)
            }
            is PhotoRequestMessage -> {
                val cardId = decoded.cardId
                val generationAtRequest = current.generation
                when {
                    // A selected-card Link grants access only to photos for cards in the
                    // frozen outgoing snapshot. The repository can see the whole local
                    // collection, so checking here is what prevents guessed ids from
                    // escaping the scope the user explicitly chose.
                    cardId !in (current.outgoing?.sharedCardIds ?: emptySet()) ->
                        mirorLinkLog("ignoring a photo request outside the shared snapshot")

                    // Already answered. The answer cannot change within a session, so
                    // repeats are free to drop.
                    cardId in current.photoServed ->
                        mirorLinkLog("ignoring a repeat photo request")

                    // Budget checked *before* the id is recorded. Recording first and
                    // refusing after would let a peer grow this set -- and queue one
                    // outbound frame per excess id -- without limit, which is the bound
                    // failing open rather than closed. Beyond the budget nothing is
                    // remembered, nothing is queued, and no coroutine starts.
                    current.photoServed.size >= MAX_PHOTO_SERVES_PER_SESSION ->
                        mirorLinkLog("photo request beyond the session budget; discarded")

                    // Busy. This consumes budget on purpose: a refusal is still a frame,
                    // so it has to be counted or it becomes the same unbounded channel.
                    current.photoServesInFlight >= MAX_CONCURRENT_PHOTO_SERVES -> {
                        current.photoServed.add(cardId)
                        scope.launch { respondPhotoUnavailable(generationAtRequest, cardId) }
                    }

                    else -> {
                        current.photoServed.add(cardId)
                        current.photoServesInFlight++
                        markProgressLocked(current)
                        scope.launch {
                            try {
                                servePhoto(generationAtRequest, cardId)
                            } finally {
                                mutex.withLock {
                                    session?.takeIf { it.generation == generationAtRequest }
                                        ?.let { it.photoServesInFlight-- }
                                }
                            }
                        }
                    }
                }
            }
            is PhotoResponseMessage -> handlePhotoResponseLocked(current, decoded)
            is PhotoChunkMessage -> handlePhotoChunkLocked(current, decoded)
            is CancelMessage -> teardownLocked(MirorLinkPhase.Cancelled, _state.value.remote)
            CompleteMessage -> maybeCompleteLocked(current)
            is ErrorMessage -> failLocked("The other phone reported a Link error.")
        }
    }

    /**
     * Records that the peer did something that moved the exchange forward.
     *
     * Called only from paths that accepted a state change, a new chunk, or bounded
     * unique photo work -- never merely because bytes arrived. This is what the idle
     * deadlines measure, so anything that refreshes it without advancing the protocol is
     * a way to keep a session alive for free.
     */
    private fun markProgressLocked(current: Session) {
        current.lastActivityAt = nowMillis()
    }

    private suspend fun handleHelloLocked(current: Session, hello: HelloMessage) {
        val selected = current.selected ?: return
        // Exactly one HELLO per session. A duplicate would otherwise relaunch the
        // snapshot send and the content offer, doing the whole exchange again on the
        // peer's say-so.
        if (current.peerHello != null) {
            mirorLinkLog("ignoring a duplicate HELLO")
            return
        }
        when (MirorLinkSelection.validateHello(hello, current.localNonce, selected.info.nonce)) {
            MirorLinkSelection.HelloValidation.Ok -> Unit

            MirorLinkSelection.HelloValidation.IncompatibleProtocol -> {
                updateRequiredLocked("Update Miror to link with this version.")
                return
            }

            // Either the peer is talking to somebody else or this is not the peer that was
            // chosen. Both mean the mutual-selection rule failed; do not share.
            else -> {
                failLocked("That phone was linking with someone else. Try again.")
                return
            }
        }

        current.peerHello = hello
        markProgressLocked(current)
        _state.value = _state.value.copy(
            peerSharesPhotos = hello.photoServe && photoCodec != null,
            phase = MirorLinkPhase.ExchangingSnapshots,
            peerNickname = hello.nickname.ifBlank { _state.value.peerNickname ?: "this Miror" },
        )

        // Both sides send concurrently. Transport initiator/responder roles carry no
        // meaning for who shares first.
        scope.launch { sendSnapshot(current.generation) }
        scope.launch { offerContentIfNewer(current.generation) }
    }

    private suspend fun sendSnapshot(generationAtSend: Int) {
        val current = mutex.withLock {
            val active = session ?: return
            if (active.generation != generationAtSend || active.snapshotSent) return
            active.snapshotSent = true
            active
        }
        // Awaited, not defaulted: before this the encode could still be in flight and the
        // peer would be told there was no collection at all.
        val outgoing = current.outgoingReady.await()
        if (mutex.withLock { session?.generation } != generationAtSend) return

        if (!mutex.withLock { sendLocked(current, outgoing.offer()) }) return
        for (index in 0 until outgoing.chunkCount) {
            val chunk = outgoing.chunk(index)
            val delivered = mutex.withLock {
                if (session?.generation != generationAtSend) return
                if (!sendLocked(current, chunk)) return@withLock false
                // Progress is reported only for a chunk that actually left the device.
                _state.value = _state.value.copy(
                    sendProgress = (index + 1).toFloat() / outgoing.chunkCount,
                )
                true
            }
            if (!delivered) return
        }
        mutex.withLock {
            if (session?.generation != generationAtSend) return
            _state.value = _state.value.copy(sendProgress = 1f)
        }
    }

    private suspend fun handleSnapshotOfferLocked(current: Session, offer: SnapshotOfferMessage) {
        // One offer per session. A replacement would reset the assembler and reallocate
        // its buffer, discarding chunks already received -- repeatable at will.
        if (current.peerOffer != null) {
            mirorLinkLog("ignoring a replacement snapshot offer")
            return
        }
        current.peerOffer = offer
        markProgressLocked(current)
        if (!offer.present) {
            // An empty or receive-only peer answers explicitly rather than leaving this
            // side waiting for a snapshot that is never coming.
            current.incomingTerminal = true
            _state.value = _state.value.copy(receiveProgress = 1f)
            if (!sendLocked(current, SnapshotResultMessage(MirorLinkResultCode.OK, null))) return
            maybeCompleteLocked(current)
            return
        }
        current.assembler = MirorLinkSnapshotAssembler(offer)
    }

    private suspend fun handleSnapshotChunkLocked(current: Session, chunk: SnapshotChunkMessage) {
        val assembler = current.assembler ?: return
        val accepted: Boolean
        try {
            // accept() reports whether the chunk was new. A duplicate is not an error and
            // not progress -- it changes nothing, so it must not extend the deadline.
            accepted = assembler.accept(chunk)
        } catch (_: MirorLinkProtocolException) {
            current.incomingTerminal = true
            // Notify: the failure below is the outcome either way, and a send that also
            // fails must not pre-empt the more specific reason.
            notifyLocked(current, SnapshotResultMessage(MirorLinkResultCode.TRANSFER_FAILED, null))
            failLocked("The shared collection arrived damaged.")
            return
        }
        if (accepted) markProgressLocked(current)
        _state.value = _state.value.copy(receiveProgress = assembler.progress)
        if (!assembler.isComplete) return

        val bytes = try {
            assembler.finish()
        } catch (_: MirorLinkProtocolException) {
            current.incomingTerminal = true
            notifyLocked(current, SnapshotResultMessage(MirorLinkResultCode.DIGEST_MISMATCH, null))
            failLocked("The shared collection failed its integrity check.")
            return
        }

        current.incomingTerminal = true
        val generationAtDecode = current.generation
        scope.launch { resolveIncoming(generationAtDecode, bytes, current.peerOffer?.scope) }
    }

    private suspend fun resolveIncoming(
        generationAtDecode: Int,
        bytes: ByteArray,
        scope: MirorLinkSnapshotScope?,
    ) {
        val resolution = runCatching { snapshots.resolve(bytes) }.getOrElse {
            MirorLinkSnapshotResolution.Invalid("The shared collection could not be read.")
        }
        mutex.withLock {
            val current = session ?: return@withLock
            if (current.generation != generationAtDecode) return@withLock
            when (resolution) {
                is MirorLinkSnapshotResolution.Resolved -> {
                    _state.value = _state.value.copy(
                        phase = MirorLinkPhase.Viewing,
                        receiveProgress = 1f,
                        remote = MirorLinkRemoteCollection(
                            collection = resolution.collection,
                            manifestBytes = bytes,
                            peerNickname = _state.value.peerNickname ?: "this Miror",
                            scope = if (scope == MirorLinkSnapshotScope.SELECTED) {
                                SharedCollectionScope.Selected
                            } else {
                                SharedCollectionScope.Full
                            },
                        ),
                    )
                    // The collection is already on screen; a failed acknowledgement is a
                    // lost session, not a lost collection, and teardown keeps the viewer.
                    if (!sendLocked(current, SnapshotResultMessage(MirorLinkResultCode.OK, null))) {
                        return@withLock
                    }
                }

                is MirorLinkSnapshotResolution.UpdateRequired -> {
                    // Notify, because the specific outcome below is the one worth telling
                    // the user about.
                    notifyLocked(
                        current,
                        SnapshotResultMessage(MirorLinkResultCode.UNSUPPORTED_FORMAT, null),
                    )
                    updateRequiredLocked(resolution.message)
                    return@withLock
                }

                is MirorLinkSnapshotResolution.Invalid -> {
                    notifyLocked(
                        current,
                        SnapshotResultMessage(MirorLinkResultCode.DECODE_FAILED, null),
                    )
                    failLocked(resolution.message)
                    return@withLock
                }
            }
            maybeCompleteLocked(current)
        }
    }

    // ----------------------------------------------------------------------- photos

    /**
     * Asks the peer for its own photo of one card. Called when the viewer opens a card
     * detail, which is why photos cost nothing for cards nobody looks at.
     *
     * Safe to call repeatedly: an already-requested or already-cached card is a no-op.
     */
    fun requestPeerPhoto(cardId: String) {
        if (photoCodec == null || cardId.isBlank()) return
        // Read here rather than cached at session start so switching it off mid-session
        // takes effect on the very next card the user opens.
        if (!receivePhotos()) return
        if (_state.value.peerPhotos.containsKey(cardId)) return
        scope.launch {
            mutex.withLock {
                val current = session ?: return@withLock
                if (_state.value.peerSharesPhotos != true) return@withLock
                if (cardId in current.photoRequested) return@withLock
                if (current.photoRequested.size >= MirorLinkProtocol.MAX_PHOTO_CACHE_ENTRIES) {
                    return@withLock
                }
                current.photoRequested.add(cardId)
                _state.value = _state.value.copy(
                    pendingPhotoCardIds = _state.value.pendingPhotoCardIds + cardId,
                )
                if (!sendLocked(current, PhotoRequestMessage(cardId))) return@withLock
            }
        }
    }

    private suspend fun servePhoto(generationAtRequest: Int, cardId: String) {
        val codec = photoCodec ?: return
        // The toggle is read at serve time, so turning sharing off mid-session takes
        // effect for the very next card the peer opens.
        if (!sharePhotos()) {
            respondPhotoUnavailable(generationAtRequest, cardId)
            return
        }
        val path = runCatching { snapshots.localPhotoPath(cardId) }.getOrNull()
        if (path == null) {
            respondPhotoUnavailable(generationAtRequest, cardId)
            return
        }
        val bytes = runCatching {
            codec.readDownscaled(path, MirorLinkProtocol.MAX_PHOTO_BYTES)
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            respondPhotoUnavailable(generationAtRequest, cardId)
            return
        }

        val chunkSize = MirorLinkProtocol.MAX_PHOTO_CHUNK_BYTES
        val chunkCount = (bytes.size + chunkSize - 1) / chunkSize
        mutex.withLock {
            val current = session ?: return
            if (current.generation != generationAtRequest) return
            if (!sendLocked(
                    current,
                    PhotoResponseMessage(
                        cardId = cardId,
                        available = true,
                        totalBytes = bytes.size,
                        chunkCount = chunkCount,
                        sha256 = MirorSha256.hash(bytes),
                    ),
                )
            ) {
                return
            }
        }
        for (index in 0 until chunkCount) {
            val start = index * chunkSize
            val end = minOf(bytes.size, start + chunkSize)
            mutex.withLock {
                val current = session ?: return
                if (current.generation != generationAtRequest) return
                if (!sendLocked(
                        current,
                        PhotoChunkMessage(cardId, index, bytes.copyOfRange(start, end)),
                    )
                ) {
                    return
                }
            }
        }
    }

    private suspend fun respondPhotoUnavailable(generationAtRequest: Int, cardId: String) {
        mutex.withLock {
            val current = session ?: return@withLock
            if (current.generation != generationAtRequest) return@withLock
            sendLocked(
                current,
                PhotoResponseMessage(cardId, available = false, 0, 0, ByteArray(32)),
            )
        }
    }

    private fun handlePhotoResponseLocked(current: Session, message: PhotoResponseMessage) {
        // Only allocate for a card this viewer explicitly requested. A photo response is
        // optional/cosmetic and must not be usable as an unsolicited allocation channel.
        if (message.cardId !in current.photoRequested) return
        // One response per request. Repeats would otherwise reset an assembly that was
        // already part-way through, letting a peer restart the work indefinitely.
        if (!current.photoAnswered.add(message.cardId)) {
            mirorLinkLog("ignoring a repeat photo response")
            return
        }
        markProgressLocked(current)
        // A peer that answers every request and finishes none would otherwise pin one
        // buffer per open card. Photos are fetched on card open, so more than a couple in
        // flight is already pathological; evict the oldest incomplete rather than grow.
        while (current.photoAssembly.size >= MAX_CONCURRENT_PHOTO_ASSEMBLIES) {
            val oldest = current.photoAssembly.keys.firstOrNull() ?: break
            current.photoAssembly.remove(oldest)
            // Evicting an incomplete photo must also forget that it was ever asked for,
            // or that card becomes permanently unretryable for the rest of the session:
            // requestPeerPhoto refuses ids already in photoRequested, so the user would
            // reopen the card and silently get nothing.
            current.photoRequested.remove(oldest)
            current.photoAnswered.remove(oldest)
            _state.value = _state.value.copy(
                pendingPhotoCardIds = _state.value.pendingPhotoCardIds - oldest,
            )
        }
        if (!message.available || message.totalBytes == 0) {
            // The peer has no photo for this card. Remember that so the viewer stops
            // spinning and never asks again this session.
            current.photoAssembly.remove(message.cardId)
            _state.value = _state.value.copy(
                pendingPhotoCardIds = _state.value.pendingPhotoCardIds - message.cardId,
            )
            return
        }
        current.photoAssembly[message.cardId] = PhotoAssembly(message)
    }

    private fun handlePhotoChunkLocked(current: Session, message: PhotoChunkMessage) {
        val assembly = current.photoAssembly[message.cardId] ?: return
        try {
            // Duplicate chunks are ignored rather than errors, and are not progress.
            if (assembly.accept(message)) markProgressLocked(current)
        } catch (_: MirorLinkProtocolException) {
            current.photoAssembly.remove(message.cardId)
            _state.value = _state.value.copy(
                pendingPhotoCardIds = _state.value.pendingPhotoCardIds - message.cardId,
            )
            return
        }
        if (!assembly.isComplete) return

        val bytes = try {
            assembly.finish()
        } catch (_: MirorLinkProtocolException) {
            // A corrupt photo is cosmetic: drop it and keep showing the catalog image.
            current.photoAssembly.remove(message.cardId)
            _state.value = _state.value.copy(
                pendingPhotoCardIds = _state.value.pendingPhotoCardIds - message.cardId,
            )
            return
        }
        current.photoAssembly.remove(message.cardId)
        // The digest above proves the bytes arrived intact, not that they are an image.
        // Refuse anything that is not a JPEG before it can reach the platform decoder.
        if (!MirorLinkImageGate.looksLikeJpeg(bytes)) {
            mirorLinkLog("rejected a non-JPEG photo payload")
            _state.value = _state.value.copy(
                pendingPhotoCardIds = _state.value.pendingPhotoCardIds - message.cardId,
            )
            return
        }
        val cardId = message.cardId
        val generationAtWrite = current.generation
        scope.launch { cachePhoto(generationAtWrite, cardId, bytes) }
    }

    private suspend fun cachePhoto(generationAtWrite: Int, cardId: String, bytes: ByteArray) {
        val codec = photoCodec ?: return
        mutex.withLock {
            val current = session ?: return@withLock
            if (current.generation != generationAtWrite) return@withLock
            // Keep the generation check and write under the same lock. Otherwise teardown
            // can clear the cache, then a late encoder write can recreate a peer photo
            // after the Link has ended.
            val path = runCatching { codec.writeSessionPhoto(cardId, bytes) }.getOrNull()
            _state.value = _state.value.copy(
                peerPhotos = if (path != null) {
                    _state.value.peerPhotos + (cardId to path)
                } else {
                    _state.value.peerPhotos
                },
                pendingPhotoCardIds = _state.value.pendingPhotoCardIds - cardId,
            )
        }
    }

    // ---------------------------------------------------------------------- content

    /**
     * Content is offered only after the collection is on screen, and only when this
     * side is strictly newer and the peer says it can install. The descriptor is cheap
     * -- no part of the package is read or hashed to build it.
     */
    private suspend fun offerContentIfNewer(generationAtOffer: Int) {
        val gateway = contentGateway ?: return
        val descriptor = runCatching { gateway.describeActive() }.getOrNull() ?: return
        mutex.withLock {
            val current = session ?: return@withLock
            if (current.generation != generationAtOffer) return@withLock
            val peer = current.peerHello ?: return@withLock
            val peerVersion = peer.contentVersion
            mirorLinkLog(
                "content offer check: local=v${descriptor.version} peer=v$peerVersion " +
                    "peerCanInstall=${peer.contentInstall}",
            )
            if (peerVersion == null) return@withLock
            if (!peer.contentInstall) return@withLock
            if (descriptor.version <= peerVersion) return@withLock
            if (!validContentOffer(descriptor)) return@withLock
            mirorLinkLog("sending content offer for ${descriptor.tag}")
            // Recorded only once the offer is actually out, so a failed send cannot leave
            // this side believing it has an offer outstanding.
            if (!sendLocked(current, descriptor)) return@withLock
            current.outgoingContentOffered = true
            current.outgoingContentDescriptor = descriptor
        }
    }

    private fun handleContentOfferLocked(current: Session, offer: ContentOfferMessage) {
        val gateway = contentGateway
        val capability = gateway?.capability()
        if (gateway == null || capability == null || !capability.canInstall) return
        // Same, older, or malformed versions produce no prompt at all.
        if (offer.version <= capability.activeVersion) return
        // And implausibly newer ones. Rejected here as well as in the installer so an
        // absurd version never reaches the user as a prompt, and no bytes move for it.
        if (offer.version - capability.activeVersion > MAX_CONTENT_VERSION_JUMP) return
        if (!validContentOffer(offer)) return

        // First offer wins for the rest of the session. A later one cannot silently
        // become what Accept applies to.
        if (current.contentOffer != null || current.acceptedOffer != null) {
            mirorLinkLog("ignoring a replacement content offer for v${offer.version}")
            return
        }
        current.contentOffer = offer
        surfaceContentOfferLocked(current)
    }

    private fun validContentOffer(offer: ContentOfferMessage): Boolean {
        if (!MirorLinkProtocol.isCanonicalContentTag(offer.tag, offer.version)) return false
        if (offer.files.map { it.name } != MirorLinkProtocol.CONTENT_FILE_NAMES) return false
        if (offer.files.any { file ->
                file.sizeBytes?.let {
                    it !in 1..MirorLinkProtocol.MAX_CONTENT_FILE_BYTES
                } == true
            }
        ) return false
        // The total is what the consent prompt shows, so it has to be pinned to
        // something. All-or-nothing: either every file size is known and the total is
        // exactly their sum, or nothing is known and no total is claimed.
        //
        // The mixed case is what a dishonest peer wants -- advertise a reassuringly small
        // total, leave one file unsized, then declare something far larger at FileBegin.
        // The genuine bundle-only donor cannot size its files cheaply and already sends
        // all-null, so refusing the mixture costs nothing real.
        val knownSizes = offer.files.mapNotNull { it.sizeBytes }
        val total = offer.totalSizeBytes
        return when (knownSizes.size) {
            offer.files.size -> {
                val sum = knownSizes.sum()
                sum in 1..MirorLinkProtocol.MAX_CONTENT_PACKAGE_BYTES && total == sum
            }
            // All-or-nothing, as documented above. A partially-sized offer was previously
            // accepted whenever it claimed no total -- harmless in itself, since FileBegin
            // still bounds every file, but it made the rule the comment describes and the
            // rule the code enforced two different things. One invariant is worth more
            // than the shape it happened to permit.
            0 -> total == null
            else -> false
        }
    }

    /**
     * Holds the offer back until the collection exchange is actually done.
     *
     * The descriptor may arrive during `HELLO`, well before the snapshot has finished
     * transferring, but the settled product decision is that collection sharing completes
     * and becomes viewable *before* any content prompt appears. Surfacing it here rather
     * than on arrival is what enforces that ordering; the peer with an empty collection
     * still reaches a terminal incoming state, so the prompt is not stranded either.
     */
    private fun surfaceContentOfferLocked(current: Session) {
        val offer = current.contentOffer ?: return
        val capability = contentGateway?.capability() ?: return
        mirorLinkLog(
            "surface offer? incomingTerminal=${current.incomingTerminal} " +
                "contentPhase=${_state.value.contentPhase} phase=${_state.value.phase}",
        )
        if (!current.incomingTerminal) return
        if (_state.value.contentPhase != MirorLinkContentPhase.None) return
        if (_state.value.phase == MirorLinkPhase.Failed) return
        mirorLinkLog("surfacing content offer v${offer.version}")

        current.acceptedOffer = offer
        _state.value = _state.value.copy(
            contentPhase = MirorLinkContentPhase.Offered,
            contentOffer = MirorLinkContentOffer(
                peerNickname = _state.value.peerNickname ?: "this Miror",
                offeredVersion = offer.version,
                localVersion = capability.activeVersion,
                totalSizeBytes = offer.totalSizeBytes,
            ),
        )
    }

    private suspend fun streamContent(
        generationAtStream: Int,
        acceptedOffer: ContentOfferMessage,
    ) {
        val gateway = contentGateway ?: return
        val outgoing = runCatching { gateway.openOutgoing() }.getOrNull()
        if (outgoing == null ||
            outgoing.tag != acceptedOffer.tag ||
            outgoing.version != acceptedOffer.version ||
            outgoing.files.map { it.name } != acceptedOffer.files.map { it.name } ||
            outgoing.files.zip(acceptedOffer.files).any { (actual, offered) ->
                offered.sizeBytes != null && offered.sizeBytes != actual.sizeBytes
            } ||
            outgoing.files.any {
                val size = it.sizeBytes
                size == null || size !in 1..MirorLinkProtocol.MAX_CONTENT_FILE_BYTES
            } ||
            outgoing.files.sumOf { it.sizeBytes ?: 0L } > MirorLinkProtocol.MAX_CONTENT_PACKAGE_BYTES
        ) {
            outgoing?.close()
            mutex.withLock {
                val current = session ?: return@withLock
                if (current.generation != generationAtStream) return@withLock
                notifyLocked(
                    current,
                    ContentResultMessage(
                        MirorLinkResultCode.TRANSFER_FAILED,
                        "The update changed before it could be sent.",
                    ),
                )
                current.contentTerminal = true
                maybeCompleteLocked(current)
            }
            return
        }
        try {
            mutex.withLock {
                val current = session ?: return
                if (current.generation != generationAtStream || current.outgoingContentAborted) {
                    return
                }
                current.outgoingContent = outgoing
            }

            outgoing.files.forEachIndexed { index, file ->
                val size = file.sizeBytes ?: 0L
                if (!sendContentFrameLocked(generationAtStream) { current ->
                        sendLocked(current, ContentFileBeginMessage(index, file.name, size))
                    }
                ) {
                    return
                }
                var offset = 0L
                var sequence = 0
                while (offset < size) {
                    val block = outgoing.read(
                        index,
                        offset,
                        MirorLinkProtocol.MAX_CONTENT_CHUNK_BYTES,
                    )
                    if (block.isEmpty()) break
                    if (!sendContentFrameLocked(generationAtStream) { current ->
                            sendLocked(current, ContentChunkMessage(index, sequence, block))
                        }
                    ) {
                        return
                    }
                    offset += block.size
                    sequence++
                }
                if (!sendContentFrameLocked(generationAtStream) { current ->
                        sendLocked(
                            current,
                            ContentFileEndMessage(index, size, outgoing.digest(index)),
                        )
                    }
                ) {
                    return
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            mirorLinkLog("content stream failed")
            mutex.withLock {
                val current = session
                if (current != null && current.generation == generationAtStream) {
                    notifyLocked(
                        current,
                        ContentResultMessage(
                            MirorLinkResultCode.TRANSFER_FAILED,
                            "The update transfer was interrupted.",
                        ),
                    )
                    current.contentTerminal = true
                    maybeCompleteLocked(current)
                }
            }
        } finally {
            // Non-suspending, so this runs even when the stream was cancelled by an abort.
            // The handles and the temporary snapshot directory are this coroutine's alone.
            outgoing.close()
            // Cancellation makes a suspending cleanup unreliable here, so the reference is
            // dropped by whoever aborts; this only covers a stream that ended on its own.
            if (kotlin.coroutines.coroutineContext[Job]?.isActive == true) {
                mutex.withLock {
                    val current = session
                    if (current?.generation == generationAtStream) {
                        current.outgoingContent = null
                        current.outgoingContentJob = null
                    }
                }
            }
        }
    }

    /**
     * One guarded step of a content stream.
     *
     * Every step needs the same three checks -- the session still exists, it is still this
     * generation, and the receiver has not abandoned the transfer -- and then has to stop
     * the stream if the frame did not go out. Writing that out five times invited exactly
     * the kind of divergence where one step forgets the abort flag.
     */
    private suspend fun sendContentFrameLocked(
        generationAtStream: Int,
        send: suspend (Session) -> Boolean,
    ): Boolean = mutex.withLock {
        val current = session ?: return@withLock false
        if (current.generation != generationAtStream) return@withLock false
        if (current.outgoingContentAborted) {
            mirorLinkLog("content stream stopping: the receiver abandoned the transfer")
            return@withLock false
        }
        send(current)
    }

    private suspend fun handleContentFileBeginLocked(
        current: Session,
        message: ContentFileBeginMessage,
    ) {
        val staging = current.staging ?: return
        val expected = current.expectedContentFiles.getOrNull(message.fileIndex)
        // The receiver only ever writes names the offer already declared, so a peer
        // cannot steer bytes at an arbitrary path.
        if (_state.value.contentPhase != MirorLinkContentPhase.Receiving ||
            current.activeContentFileIndex != null ||
            message.fileIndex != current.nextContentFileIndex ||
            expected == null ||
            expected.name != message.name ||
            message.sizeBytes !in 1..MirorLinkProtocol.MAX_CONTENT_FILE_BYTES ||
            (expected.sizeBytes != null && expected.sizeBytes != message.sizeBytes) ||
            current.contentBytesReceived + message.sizeBytes >
            MirorLinkProtocol.MAX_CONTENT_PACKAGE_BYTES
        ) {
            abandonContentLocked(current, "The update did not match what was offered.")
            return
        }
        val began = runCatching {
            staging.beginFile(message.fileIndex, message.name, message.sizeBytes)
        }.isSuccess
        if (!began) {
            abandonContentLocked(current, "Miror could not store the received update.")
            return
        }
        current.activeContentFileIndex = message.fileIndex
        current.nextContentSequence = 0
        current.activeContentFileBytes = 0L
        // The declared size becomes the contract for every chunk that follows, and fixes
        // exactly how many there may be.
        current.activeContentFileExpected = message.sizeBytes
        current.activeContentChunkCount =
            (message.sizeBytes + MirorLinkProtocol.MAX_CONTENT_CHUNK_BYTES - 1) /
                MirorLinkProtocol.MAX_CONTENT_CHUNK_BYTES
        markProgressLocked(current)
        if (current.contentBytesExpected == 0L) {
            current.contentBytesExpected = current.expectedContentFiles
                .sumOf { it.sizeBytes ?: 0L }
                .takeIf { it > 0 } ?: message.sizeBytes
        }
    }

    /**
     * Strict framing, because "in order and within the total" was not enough.
     *
     * A sequence-correct chunk of zero or one byte used to be accepted and to count as
     * progress, so a peer could hold an accepted transfer -- and with it the radios, the
     * staging directory and the screen -- open indefinitely while advancing the file by
     * almost nothing. Every chunk's length is now fully determined by the declared file
     * size, so there is no room to trickle.
     *
     * The sender already emits full chunks plus one exact remainder, so this describes
     * what it has always produced.
     */
    private suspend fun handleContentChunkLocked(current: Session, message: ContentChunkMessage) {
        val staging = current.staging ?: return
        val activeIndex = current.activeContentFileIndex
        val remaining = current.activeContentFileExpected - current.activeContentFileBytes
        val requiredLength = minOf(
            MirorLinkProtocol.MAX_CONTENT_CHUNK_BYTES.toLong(),
            remaining,
        )
        if (activeIndex != message.fileIndex ||
            message.sequence != current.nextContentSequence ||
            // Past the last chunk the declared size allows.
            current.nextContentSequence >= current.activeContentChunkCount ||
            remaining <= 0L ||
            // Exactly the length the declared size requires -- empty, short, or long all
            // fail closed rather than being absorbed.
            message.data.size.toLong() != requiredLength ||
            current.contentBytesReceived + message.data.size >
            MirorLinkProtocol.MAX_CONTENT_PACKAGE_BYTES
        ) {
            abandonContentLocked(current, "The update arrived out of order and was not installed.")
            return
        }
        if (runCatching { staging.write(message.fileIndex, message.data) }.isFailure) {
            abandonContentLocked(current, "Miror could not store the received update.")
            return
        }
        // Sequence-checked above, so reaching here means these bytes are new.
        current.contentBytesReceived += message.data.size
        current.activeContentFileBytes += message.data.size
        current.nextContentSequence++
        markProgressLocked(current)
        val progressExpected = current.contentBytesExpected
        if (progressExpected > 0) {
            _state.value = _state.value.copy(
                contentProgress =
                    (current.contentBytesReceived.toFloat() / progressExpected).coerceIn(0f, 1f),
            )
        }
    }

    private suspend fun handleContentFileEndLocked(current: Session, message: ContentFileEndMessage) {
        val staging = current.staging ?: return
        val expected = current.expectedContentFiles.getOrNull(message.fileIndex)
        if (current.activeContentFileIndex != message.fileIndex ||
            expected == null ||
            message.totalBytes != current.activeContentFileBytes ||
            // Must also match what FileBegin declared, so the two ends of one file
            // cannot disagree about its length.
            message.totalBytes != current.activeContentFileExpected ||
            (expected.sizeBytes != null && expected.sizeBytes != message.totalBytes)
        ) {
            abandonContentLocked(current, "The update did not match what was offered.")
            return
        }
        val ok = runCatching {
            staging.finishFile(message.fileIndex, message.totalBytes, message.sha256)
        }.getOrDefault(false)
        if (!ok) {
            abandonContentLocked(current, "The update arrived damaged and was not installed.")
            return
        }
        current.activeContentFileIndex = null
        current.nextContentFileIndex++
        current.nextContentSequence = 0
        current.activeContentFileBytes = 0L
        current.activeContentFileExpected = 0L
        current.activeContentChunkCount = 0L
        markProgressLocked(current)
        if (message.fileIndex != current.expectedContentFiles.lastIndex) return

        val offer = current.acceptedOffer ?: return
        _state.value = _state.value.copy(contentPhase = MirorLinkContentPhase.Applying)
        val generationAtInstall = current.generation
        scope.launch { installContent(generationAtInstall, staging, offer.tag, offer.version) }
    }

    private suspend fun installContent(
        generationAtInstall: Int,
        staging: MirorLinkContentStaging,
        tag: String,
        version: Int,
    ) {
        val gateway = contentGateway ?: return
        val result = runCatching { gateway.installVerified(staging, tag, version) }
            .getOrElse { MirorLinkContentInstallResult.Rejected("The update could not be installed.") }

        var shouldReResolve = false
        mutex.withLock {
            val current = session
            // Installation itself is crash-safe and may finish after the radio session is
            // closed, but its late completion must not resurrect or overwrite a newer
            // Link's UI state.
            if (current == null || current.generation != generationAtInstall) {
                return@withLock
            }
            val message = when (result) {
                MirorLinkContentInstallResult.Applied -> null
                MirorLinkContentInstallResult.Busy ->
                    "Miror is already updating card data. Try again in a moment."
                MirorLinkContentInstallResult.Superseded ->
                    "Your card data is already up to date."
                is MirorLinkContentInstallResult.Rejected -> result.reason
            }
            _state.value = _state.value.copy(
                contentPhase = if (result is MirorLinkContentInstallResult.Applied) {
                    MirorLinkContentPhase.Applied
                } else {
                    MirorLinkContentPhase.Failed
                },
                contentOffer = null,
                contentMessage = message,
                contentProgress = 1f,
            )
            current.contentTerminal = true
            current.staging = null
            notifyLocked(
                current,
                ContentResultMessage(
                    if (result is MirorLinkContentInstallResult.Applied) {
                        MirorLinkResultCode.OK
                    } else {
                        MirorLinkResultCode.INSTALL_FAILED
                    },
                    null,
                ),
            )
            maybeCompleteLocked(current)
            shouldReResolve = result is MirorLinkContentInstallResult.Applied
        }

        // Newly installed content can name cards the peer's manifest referenced but this
        // device could not resolve a moment ago. Re-resolve in place; the viewer stays up.
        if (shouldReResolve) reResolveRemoteCollection()
    }

    /**
     * Gives up on an incoming package and tells the donor to stop sending it.
     *
     * The notification is the point. Without it the donor kept reading and pushing the
     * whole ~57 MiB while this side dropped every chunk on the floor -- minutes of radio,
     * battery and disk on both phones for a transfer that had already failed. It is sent
     * with [notifyLocked] rather than [sendLocked] because a content abort must never
     * escalate into the end of a collection exchange that has otherwise completed.
     */
    private suspend fun abandonContentLocked(current: Session, message: String) {
        current.staging?.abandon()
        current.staging = null
        current.contentTerminal = true
        current.contentOffer = null
        current.activeContentFileIndex = null
        current.activeContentFileExpected = 0L
        current.activeContentChunkCount = 0L
        _state.value = _state.value.copy(
            contentPhase = MirorLinkContentPhase.Failed,
            contentOffer = null,
            contentMessage = message,
        )
        // Fixed code, no reason string: the donor has no use for our copy, and the text
        // shown here is ours rather than something to put on the wire.
        notifyLocked(current, ContentResultMessage(MirorLinkResultCode.TRANSFER_FAILED, null))
    }

    /**
     * Stops an outgoing package this side is streaming.
     *
     * Deliberately does **not** close the handles. `streamContent` owns them and closes
     * them in its own non-suspending `finally`, and closing them from here would race a
     * read that is already in progress -- turning a clean stop into an IO error on a file
     * the stream is mid-way through. Cancelling covers a step parked on the transport; the
     * flag covers one that has not reached its next check yet; and the reference is dropped
     * here because a cancelled `finally` cannot be relied on to take the lock.
     */
    private fun stopOutgoingContentLocked(current: Session) {
        if (current.outgoingContent == null && current.outgoingContentJob == null) return
        current.outgoingContentAborted = true
        current.outgoingContentJob?.cancel()
        current.outgoingContentJob = null
        current.outgoingContent = null
    }

    // ----------------------------------------------------------------- terminal state

    /**
     * The session ends only once both snapshot exchanges are terminal and any content
     * offer has been accepted-and-finished, declined, or cancelled.
     *
     * The no-remote branch is load-bearing and was found on real hardware: when the peer
     * has an empty collection it correctly answers with an absent snapshot, but without
     * this the local side had nothing to transition to and sat on "Swapping collections"
     * forever. A peer with nothing to share is a *completed* Link, not a stalled one.
     */
    private fun maybeCompleteLocked(current: Session) {
        val collectionsDone = current.incomingTerminal && current.outgoingTerminal
        val contentDone = current.contentOffer == null || current.contentTerminal
        // An offer that arrived early becomes visible only now, once the collection has.
        surfaceContentOfferLocked(current)

        if (!collectionsDone || !contentDone) return
        if (_state.value.phase == MirorLinkPhase.Failed) return
        // A surfaced-but-unanswered offer keeps the session alive.
        if (_state.value.contentPhase == MirorLinkContentPhase.Offered) return

        _state.value = if (_state.value.remote != null) {
            _state.value.copy(phase = MirorLinkPhase.Viewing)
        } else {
            _state.value.copy(phase = MirorLinkPhase.Complete)
        }
    }

    private suspend fun updateRequiredLocked(message: String) {
        _state.value = _state.value.copy(updateRequired = true)
        failLocked(message)
    }

    /**
     * Ends the session and says why.
     *
     * This used to set UI state only, which made "failed" a label rather than a state:
     * the endpoint stayed connected, further frames were still accepted and acted on, and
     * staging was left on disk -- while [MirorLinkUiState.isActive] reported false, so
     * nothing in the UI offered to stop it either. A session the user has been told is
     * over must actually be over.
     *
     * An already-received collection survives, exactly as it does for an ordinary
     * disconnect: it is read-only and its arrival is not undone by a later failure.
     */
    private suspend fun failLocked(message: String) {
        if (_state.value.phase == MirorLinkPhase.Failed && session == null) return
        val remote = _state.value.remote
        _state.value = _state.value.copy(phase = MirorLinkPhase.Failed, failure = message)
        teardownLocked(MirorLinkPhase.Failed, remote)
    }

    /**
     * Single teardown path. Radios, pending connections, queued frames, signal samples,
     * and incomplete staging all end here; only the chosen nickname survives a session.
     */
    private suspend fun teardownLocked(
        phase: MirorLinkPhase,
        keepRemote: MirorLinkRemoteCollection?,
        preserveRadios: Boolean = false,
    ) {
        val ending = session
        session = null
        generation++
        val generationAfterTeardown = generation
        eventJob?.cancel()
        eventJob = null
        lifecycleJob?.cancel()
        lifecycleJob = null
        proximity.clear()
        proximitySamples.discardPending()

        ending?.staging?.abandon()
        if (ending != null) {
            // Cancel first so no further step can start, then release what the stream may
            // not get a chance to. `close` tolerates running after the stream's own.
            ending.outgoingContentAborted = true
            ending.outgoingContentJob?.cancel()
            ending.outgoingContentJob = null
            ending.outgoingContent?.close()
            ending.outgoingContent = null
        }
        ending?.pendingInbound?.clear()
        ending?.candidates?.clear()
        ending?.photoAssembly?.clear()
        ending?.photoRequested?.clear()
        ending?.photoServed?.clear()
        ending?.photoAnswered?.clear()
        // Received photos are session-scoped, like the collection itself is read-only and
        // temporary. Nothing a peer sent survives the Link.
        photoCodec?.clearSessionPhotos()

        proximitySource?.let { source ->
            scope.launch {
                proximityPlatformMutex.withLock {
                    // This detached cleanup may be delayed behind a platform start/stop.
                    // Once a replacement session exists, its own start calls own these
                    // shared BLE objects; an old teardown must not stop them.
                    val stillUnowned = mutex.withLock {
                        session == null && generation == generationAfterTeardown
                    }
                    if (stillUnowned) {
                        runCatching { source.stopScan() }
                        runCatching { source.stopBeacon() }
                    }
                }
            }
        }
        // preserveRadios is only ever set when a pre-warm is being promoted into a real
        // session: shutting the transport down there would discard the very warm-up the
        // pre-warm just paid for.
        if (!preserveRadios) {
            runCatching { transport.shutdown() }
            if (ending != null) lastRadioTeardownAt = nowMillis()
        }

        _state.value = MirorLinkUiState(
            // A retained collection ends in Complete, not Viewing. Viewing means "session
            // live, collection on screen"; once the radios are down the session is over
            // even though the read-only collection stays. Reporting Viewing here left
            // isActive true, so End Link stopped the radios and then still offered to
            // stop them -- it looked like the button did nothing.
            phase = if (keepRemote != null && phase != MirorLinkPhase.Failed) {
                MirorLinkPhase.Complete
            } else {
                phase
            },
            availability = _state.value.availability,
            nickname = _state.value.nickname,
            peerNickname = _state.value.peerNickname,
            remote = keepRemote,
            contentPhase = _state.value.contentPhase,
            contentMessage = _state.value.contentMessage,
            // Survives teardown on purpose: it explains why the session that just ended
            // did so, and it is only ever set alongside a retained collection.
            sessionNotice = _state.value.sessionNotice,
            failure = if (phase == MirorLinkPhase.Failed) _state.value.failure else null,
            updateRequired = _state.value.updateRequired,
            peerPhotos = emptyMap(),
            pendingPhotoCardIds = emptySet(),
            peerSharesPhotos = null,
        )
    }

    companion object {
        /**
         * Shown whenever a frame could not be sent, whatever the underlying reason.
         *
         * Deliberately one fixed string. The three causes -- an encoder budget, the send
         * deadline, and the platform refusing an endpoint that has gone away -- are the
         * same event to the person holding the phone, and the platform's own text is not
         * product copy: it carries endpoint identifiers and SDK internals.
         */
        internal const val SEND_FAILURE_MESSAGE = "Miror Link lost its connection. Try again."

        /**
         * Shown when the peer is addressing a Link session identity this side does not hold.
         *
         * Almost always means they cancelled and reopened Link while this phone was still
         * connecting to the previous attempt. Accurate and actionable: the fix really is to
         * tap Link again, and both sides now mint fresh identities when they do.
         */
        internal const val SESSION_CHANGED_MESSAGE =
            "The other phone restarted its Link session. Try again."

        /**
         * How long a selected peer may go without a connection before this side calls.
         *
         * Long enough that a healthy connection -- well under a second on both test devices
         * -- is never raced, short enough that a mutual abstention is invisible next to the
         * 90 second discovery deadline it replaces.
         */
        const val DEFAULT_INITIATION_FALLBACK_MILLIS = 2_500L

        /**
         * Time allowed for the inbound half of simultaneous initiation to complete.
         *
         * Hardware showed Nearby fail one outbound request and then deliver a successful
         * inbound connection about one second later. Two seconds avoids tearing that winner
         * down while still returning a genuinely dead endpoint to discovery promptly.
         */
        const val DEFAULT_CONNECTION_FAILURE_GRACE_MILLIS = 2_000L

        /**
         * Maximum time one platform radio startup may own [radioStartupMutex].
         *
         * Nearby normally completes in roughly one to three seconds on the test devices,
         * but a rapid stop/start under real radio contention reached 9.5 seconds on the
         * Pixel 9. Twenty seconds leaves more than twice that observed stressed duration.
         * A Task still unfinished then is a platform failure, not ordinary slow discovery,
         * and must not prevent every later Link or pre-warm in this process.
         */
        const val DEFAULT_RADIO_STARTUP_TIMEOUT_MILLIS = 20_000L

        /**
         * Settling time after a real Link session tears Nearby down.
         *
         * Only rapid cancel/restart pays this cost. On both physical Pixels, immediately
         * reopening during Play Services' asynchronous endpoint cleanup produced repeated
         * request refusals even after application-level nonce/endpoint reconciliation was
         * correct. A four-second pause matches the passing hardware control and prevents
         * stale platform connection state from being mistaken for a usable replacement.
         */
        const val DEFAULT_RADIO_RESTART_COOLDOWN_MILLIS = 4_000L

        const val DEFAULT_STABILITY_WINDOW_MILLIS = 650L
        const val DEFAULT_CHOOSER_DELAY_MILLIS = 2_000L
        const val DEFAULT_INBOUND_HOLD_MILLIS = 1_200L
        private const val POLL_INTERVAL_MILLIS = 100L

        /**
         * Mirrors `ContentUpdateManager.MAX_PEER_VERSION_JUMP`. Deliberately restated
         * rather than shared: this module is common code and the installer is Android
         * only. The installer is the security boundary; this is the early-out that keeps
         * an absurd offer from ever becoming a user-facing prompt.
         */
        const val MAX_CONTENT_VERSION_JUMP = 4

        /**
         * How long to look for a peer before giving up.
         *
         * Bounds the window in which the phone is advertising to everyone in range --
         * the only exposure lever available for third-party radio stack bugs, which are
         * not ours to fix. Generous against measured reality: radios come up in ~70ms and
         * a snapshot builds in ~110ms, so this is sized for two people coordinating
         * ("open it now"), not for the protocol.
         *
         * Scoped to discovery only. Once connected, a content transfer can legitimately
         * run for minutes, and advertising has already stopped.
         */
        const val DEFAULT_DISCOVERY_TIMEOUT_MILLIS = 90_000L

        /**
         * How long a connected session may make no progress before it is ended.
         *
         * A content relay legitimately runs for minutes, but it always advances. A peer
         * that stops sending is not slow, it is gone -- and holding the connection open
         * keeps radios and buffers alive for nothing.
         */
        const val DEFAULT_INACTIVITY_TIMEOUT_MILLIS = 60_000L

        /**
         * How long an uncommitted phone may keep discovery warm.
         *
         * Pre-warming exists to hide Nearby's stack startup behind the moment the user is
         * still deciding. Past a minute they have stopped deciding, and a share sheet left
         * open should not mean a radio left running.
         */
        const val DEFAULT_PREWARM_TIMEOUT_MILLIS = 60_000L

        /**
         * Peers tracked at once, in any one map.
         *
         * Peer identity is unauthenticated and free to mint, so every map keyed by it is
         * a growth surface. No real room has this many phones running Miror Link, and
         * beyond the cap the extra entries would only crowd the chooser anyway.
         */
        const val MAX_TRACKED_PEERS = 32

        /**
         * Local ephemeral identities retained solely to reject cached self-advertisements.
         *
         * Equal to the peer cap so even pathological user-driven restart loops remain tiny;
         * ordinary use retains far more history than Nearby's short advertisement cache.
         */
        const val MAX_RECENT_LOCAL_NONCES = MAX_TRACKED_PEERS

        /**
         * Connection attempts allowed per endpoint id before it is retired for the session.
         *
         * Two, not one: the first rejection is often a peer that was still settling its own
         * selection, and retrying it is the whole point of the selection-restart path. Two,
         * not more: past that the id is not going to answer, and re-selecting it is the
         * churn loop that hammered the radio on hardware.
         */
        const val MAX_ENDPOINT_ATTEMPTS = 2

        /**
         * Session-wide ceiling across endpoint ids and nonce rotations.
         *
         * The per-identity allowance supports a genuine peer restarting Link; this second
         * bound prevents an unauthenticated advertiser from minting new nonces forever to
         * evade it. It preserves the original worst case of two attempts for each tracked
         * peer.
         */
        const val MAX_TOTAL_ENDPOINT_FAILURES = MAX_TRACKED_PEERS * MAX_ENDPOINT_ATTEMPTS

        /**
         * Idle budget once a collection is on screen.
         *
         * Longer than [DEFAULT_INACTIVITY_TIMEOUT_MILLIS] because the user is reading,
         * and every card they open still fetches a photo over this connection. Ten
         * minutes rather than five: the user opened Link deliberately and normally
         * dismisses it when done, and a real trade conversation can easily run that long
         * without either phone being touched.
         *
         * Still bounded, and still cheap to be wrong about: Link is foreground-only, so
         * this only ever runs with the app open in front of someone, and expiry keeps the
         * shared collection on screen.
         */
        const val DEFAULT_VIEWING_IDLE_TIMEOUT_MILLIS = 10 * 60_000L

        /**
         * Absolute ceiling on one accepted content transfer, from acceptance.
         *
         * Generous against reality -- a ~57 MiB package moves in about a minute over
         * Nearby's Wi-Fi path -- and never extended by traffic, which is the point: the
         * idle deadline answers "did anything arrive", this one answers "is this ever
         * going to finish".
         */
        const val DEFAULT_CONTENT_TRANSFER_TIMEOUT_MILLIS = 10 * 60_000L

        /** Longest a single frame may take before the session is treated as stalled. */
        const val DEFAULT_SEND_TIMEOUT_MILLIS = 10_000L

        /** How long to spend telling a peer we cancelled before releasing the radios. */
        private const val CANCEL_NOTICE_TIMEOUT_MILLIS = 1_000L

        /** Deadline checks are coarse; they bound exposure, not user-visible latency. */
        private const val DEADLINE_POLL_INTERVAL_MILLIS = 500L

        /** Concurrent decode/downscale jobs this side will run for a peer. */
        const val MAX_CONCURRENT_PHOTO_SERVES = 2

        /**
         * Distinct cards this side will serve in one session.
         *
         * Matches the viewer's own request cap, so a well-behaved peer never reaches it
         * while a peer asking for the whole catalog stops being answered.
         */
        const val MAX_PHOTO_SERVES_PER_SESSION = MirorLinkProtocol.MAX_PHOTO_CACHE_ENTRIES

        /** Photo reassembly buffers held at once. Each is at most MAX_PHOTO_BYTES. */
        const val MAX_CONCURRENT_PHOTO_ASSEMBLIES = 4

        private fun defaultClock(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }

        /**
         * Both phones derive the same session id from the two nonces without an extra
         * negotiation round trip, so `HELLO` -- the first message -- can already be
         * addressed to a session both sides agree on.
         */
        internal fun sessionIdFor(a: ByteArray, b: ByteArray): ByteArray {
            val ordered = if (MirorLinkSelection.compareNonces(a, b) <= 0) a to b else b to a
            return MirorSha256().apply {
                update("miror.link.session.v1".encodeToByteArray())
                update(ordered.first)
                update(ordered.second)
            }.digest().copyOfRange(0, MirorLinkProtocol.SESSION_ID_BYTES)
        }
    }
}

private fun MirorLinkAvailability.toUi(): MirorLinkAvailabilityUi = when (this) {
    MirorLinkAvailability.Available -> MirorLinkAvailabilityUi.Ready
    MirorLinkAvailability.Unsupported -> MirorLinkAvailabilityUi.Unsupported
    MirorLinkAvailability.PermissionsRequired -> MirorLinkAvailabilityUi.NeedsPermissions
    MirorLinkAvailability.RadiosOff -> MirorLinkAvailabilityUi.RadiosOff
}
