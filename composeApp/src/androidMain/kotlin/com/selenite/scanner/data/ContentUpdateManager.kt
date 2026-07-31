package com.selenite.scanner.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.head
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * Fetches card content published as GitHub Releases on the Miror repository.
 *
 * Releases are used rather than the git tree or the GitHub API on purpose.
 * Release assets are served from a CDN and are not rate limited; the
 * unauthenticated API allows 60 requests per hour per IP, and mobile clients
 * behind carrier NAT share addresses, so API polling fails in ways that never
 * reproduce on a developer's own device.
 *
 * A content release ships [ContentAssets.REQUIRED_FILES] as a matched set. The
 * card database and the embedding gallery must move together -- a database
 * carrying a new set alongside a gallery without that set's embeddings yields
 * cards that appear in the app but cannot be scanned.
 */
object ContentUpdateManager {
    private const val TAG = "ContentUpdateManager"
    /** Reset only by a new process, i.e. a genuine cold start. */
    @Volatile
    private var checkedThisProcess = false
    private const val REPO = "MilkSR/Miror"
    private const val LATEST_URL = "https://github.com/$REPO/releases/latest"
    private const val DOWNLOAD_BASE = "https://github.com/$REPO/releases/download"
    private const val GLOBAL_EMBEDDING_DIM = 512
    private const val FP16_BYTES = 2

    /**
     * How far ahead of the active version a peer-relayed package may claim to be.
     *
     * Content versions increment by one per release, so a peer legitimately several
     * ahead is implausible. The bound exists because every other version check is
     * *relative* ("newer than mine") and its result is *permanent* -- an accepted
     * version becomes the floor for all future comparisons, including the over-the-air
     * update check. Without an absolute ceiling, one accepted absurd version silently
     * ends content updates for that device forever.
     */
    const val MAX_PEER_VERSION_JUMP = 4

    // MainActivity.onCreate runs again on things like a configuration change or
    // a process restart, so two update passes can overlap. They would share one
    // staging directory, and whichever started second would clear the first's
    // in-flight files out from under it. Only one pass may run at a time; a
    // second gives up rather than queueing, since the first is already doing it.
    private val updateInFlight = Mutex()

    /**
     * Startup work runs here rather than on the Activity's `lifecycleScope`.
     *
     * MainActivity declares no `configChanges`, so a theme change -- including
     * the automatic one at sunset -- destroys and recreates it, cancelling
     * `lifecycleScope` and every coroutine started from it. A content download
     * in flight dies with it: verified on device, a config change mid-transfer
     * raised `CancellationException` inside the Ktor engine, and the pass the
     * new Activity started was then turned away by [updateInFlight], so the
     * update silently did not happen until the next cold start.
     *
     * The user-accepted download already avoided this by running on a scope
     * ContentUpdatePrompt owns. The silent one needs the same guarantee.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards against a recreated Activity starting a second startup pass. */
    @Volatile
    private var startupJob: Job? = null

    /**
     * Seeds the database, merges any newer bundled or installed content, and
     * then looks for a new release -- all on a scope that outlives the Activity.
     *
     * Safe to call from every `onCreate`: a pass already running is left alone
     * rather than duplicated, which matters because two concurrent
     * [DatabaseSyncManager.performSafeSync] calls would merge the same rows
     * twice over each other.
     */
    fun launchStartupSync(context: Context, syncManager: DatabaseSyncManager) {
        if (startupJob?.isActive == true) {
            Log.d(TAG, "Startup sync already running; not starting another")
            return
        }
        val appContext = context.applicationContext
        startupJob = appScope.launch {
            DatabaseHelper.copyDatabaseFromAssets(appContext)

            // The startup merge takes the same lock every install takes, so the
            // two can never write card rows at once. The guard above stops a
            // recreated Activity duplicating this job, but it cannot help once
            // the first job has already finished by *offering* a metered
            // update: that job is complete while the download the user later
            // accepts is still running, leaving a fresh startup pass free to
            // merge underneath it.
            //
            // Held only across the merge and released before the update check,
            // which acquires the same lock itself -- holding it across both
            // would make that acquisition fail and silently disable OTA.
            updateInFlight.withLock {
                syncManager.performSafeSync()
            }
            autoUpdateOnStartup(appContext, syncManager)
        }
    }

    data class RemoteContent(val tag: String, val version: Int)

    private sealed interface PendingRecovery {
        data object None : PendingRecovery
        data class Applied(val tag: String) : PendingRecovery
        data class Failed(val tag: String, val reason: String) : PendingRecovery
    }

    sealed interface CheckResult {
        data class UpdateAvailable(val remote: RemoteContent, val installedVersion: Int) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val cause: Throwable) : CheckResult
    }

    /**
     * The content version currently in use: an over-the-air install when one is
     * present, otherwise the version bundled in the APK.
     */
    fun activeVersion(context: Context): Int =
        ContentAssets.installedVersion(context) ?: DatabaseSyncManager.CURRENT_ASSET_VERSION

    /**
     * [forDownload] gets a long ceiling because a 57MB transfer on a slow
     * connection is legitimately slow; the socket timeout still catches a
     * stalled one. Probes get short ceilings across the board.
     *
     * Timeouts are not optional here. Without them a request that never
     * answers blocks the update pass forever, which on the metered path means
     * the prompt is never offered and on the unmetered path means the install
     * never starts -- silently, with the coroutine parked.
     */
    private fun httpClient(followRedirects: Boolean, forDownload: Boolean = false) =
        HttpClient(OkHttp) {
            this.followRedirects = followRedirects
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = if (forDownload) 60_000 else 20_000
                requestTimeoutMillis = if (forDownload) 30L * 60L * 1000L else 20_000
            }
        }

    /**
     * Resolves the newest published content tag without downloading anything.
     *
     * `releases/latest` answers with a redirect to the tagged release, so the
     * tag can be read from the Location header. This costs one small request
     * and needs no manifest file or API call.
     */
    suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        try {
            val tag = httpClient(followRedirects = false).use { client ->
                val response = client.head(LATEST_URL)
                val location = response.headers[HttpHeaders.Location]
                    ?: error("No Location header on $LATEST_URL (status ${response.status})")
                Regex("""/tag/([^/?#]+)""").find(location)?.groupValues?.get(1)
                    ?: error("Could not read a tag from redirect: $location")
            }

            val version = ContentAssets.parseVersion(tag)
                ?: error("Unexpected tag format: $tag")
            val installed = activeVersion(context)

            Log.i(TAG, "Latest content is $tag (v$version); installed v$installed")
            if (version > installed) {
                CheckResult.UpdateAvailable(RemoteContent(tag, version), installed)
            } else {
                CheckResult.UpToDate
            }
        } catch (e: Exception) {
            Log.e(TAG, "Content check failed", e)
            CheckResult.Failed(e)
        }
    }

    /**
     * Downloads a release into staging and moves it into its tag directory once
     * every required file has arrived. Returns true when the complete set is on
     * disk.
     *
     * Deliberately stops short of making the content live. Promotion happens in
     * [installLocked], after the database has been proven readable -- a release
     * this build cannot parse must never become the content it runs on.
     *
     * [onProgress] receives bytes downloaded and the total across all files, or
     * -1 for the total when the server does not report content lengths.
     */
    suspend fun download(
        context: Context,
        remote: RemoteContent,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val staging = File(ContentAssets.stagingDir(context), remote.tag)
        try {
            ContentAssets.clearStaging(context)
            if (!staging.mkdirs() && !staging.isDirectory) {
                error("Could not create staging directory: $staging")
            }

            httpClient(followRedirects = true, forDownload = true).use { client ->
                val total = totalSize(client, remote.tag)
                var downloaded = 0L

                for (name in ContentAssets.REQUIRED_FILES) {
                    val url = "$DOWNLOAD_BASE/${remote.tag}/$name"
                    val target = File(staging, name)
                    Log.i(TAG, "Downloading $name")

                    var expected = -1L
                    client.prepareGet(url).execute { response ->
                        if (!response.status.isSuccess()) {
                            error("$name returned ${response.status}")
                        }
                        expected = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
                        target.outputStream().use { fileOut ->
                            val counting = object : FilterOutputStream(fileOut) {
                                override fun write(b: ByteArray, off: Int, len: Int) {
                                    out.write(b, off, len)
                                    downloaded += len
                                    onProgress(downloaded, total)
                                }
                            }
                            response.bodyAsChannel().copyTo(counting as OutputStream)
                        }
                    }

                    if (target.length() <= 0) error("$name downloaded empty")
                    // A connection dropped mid-transfer yields a short file, not
                    // an error. Unnoticed, a truncated gallery empties the search
                    // index and identification stops working until the next
                    // reload, so a length that disagrees with the server's fails
                    // the whole set and leaves the previous content live.
                    if (expected >= 0 && target.length() != expected) {
                        error("$name is truncated: got ${target.length()} bytes, expected $expected")
                    }
                }
            }

            // Move the completed set out of staging, so nothing later points
            // inside a directory we are about to clear.
            val installDir = ContentAssets.tagDir(context, remote.tag)
            installDir.deleteRecursively()
            if (!staging.renameTo(installDir)) {
                staging.copyRecursively(installDir, overwrite = true)
                staging.deleteRecursively()
            }

            val complete = ContentAssets.isComplete(installDir)
            if (complete) Log.i(TAG, "Staged content ${remote.tag}")
            complete
        } catch (e: Exception) {
            Log.e(TAG, "Content download failed for ${remote.tag}", e)
            false
        } finally {
            ContentAssets.clearStaging(context)
        }
    }

    /**
     * Startup entry point: compares the installed content version against the
     * newest published tag and installs it when the device is on an unmetered
     * connection.
     *
     * The comparison itself costs one HEAD request and never touches the
     * assets, so it is cheap enough to run on every launch. The ~57MB download
     * is what gets held back -- pulling that over cellular unprompted can cost
     * a user real money, so on a metered connection the available tag is
     * recorded and left for an explicit trigger.
     */
    suspend fun autoUpdateOnStartup(
        context: Context,
        syncManager: DatabaseSyncManager,
        offerAlways: Boolean = false,
    ): ApplyResult = withContext(Dispatchers.IO) {
        if (!updateInFlight.tryLock()) {
            Log.d(TAG, "A content update is already running; skipping this pass")
            return@withContext ApplyResult.UpToDate
        }

        // The check runs under the lock, then releases it. The transfer takes
        // the lock again in installLocked, so a download the user accepted
        // minutes later is protected exactly like a silent one -- without this
        // split, the metered path would return, drop the lock, and leave a
        // later pass free to clear staging out from under an active download.
        var failedPending: PendingRecovery.Failed? = null
        val available = try {
            // A complete download is a durable local recovery point. Finish it
            // before asking the network for anything; this also handles a
            // process death after the atomic DB merge but before marker
            // activation/version acknowledgement.
            when (val recovery = recoverPendingLocked(context, syncManager)) {
                PendingRecovery.None -> Unit
                is PendingRecovery.Applied -> {
                    return@withContext ApplyResult.Applied(recovery.tag)
                }
                is PendingRecovery.Failed -> {
                    failedPending = recovery
                    Log.w(TAG, "Pending ${recovery.tag} did not apply; checking whether a newer release supersedes it")
                }
            }

            // Reclaim ~57MB if an app update has caught up with an OTA install.
            ContentAssets.pruneSuperseded(context)

            // Then reclaim any tag directory no marker still points at, which
            // is what a superseded failed install leaves behind. Ordered after
            // pruneSuperseded so a version-superseded directory is already gone
            // and after recovery so a just-applied pending has been finished.
            ContentAssets.pruneOrphanedTags(context)

            // Staging only ever holds an in-flight download. Anything here at
            // startup is the remains of a process that died mid-transfer, so it
            // is dead weight -- up to 57MB of it -- and is never resumable.
            ContentAssets.clearStaging(context)

            // Once per process, which is once per cold start. A warm resume
            // reuses the process and skips; so does a configuration change,
            // which also re-runs MainActivity.onCreate and would otherwise
            // re-check on every rotation.
            //
            // There is deliberately no time-based cooldown. The check is a
            // single HEAD against a CDN, not the rate-limited API, so it costs
            // nothing worth rationing -- and a cooldown only means a user who
            // opened the app just before a release waits out the timer for
            // content that is already published.
            if (checkedThisProcess) {
                failedPending?.let {
                    return@withContext ApplyResult.Failed(it.reason)
                }
                Log.d(TAG, "Content already checked in this process; skipping")
                return@withContext ApplyResult.UpToDate
            }
            checkedThisProcess = true

            when (val check = check(context)) {
                is CheckResult.Failed -> {
                    // Offline or GitHub unreachable is normal, not an error
                    // state. Allow a retry later in this process, since
                    // connectivity can return without the app being restarted.
                    checkedThisProcess = false
                    return@withContext ApplyResult.Failed(
                        failedPending?.reason ?: check.cause.message ?: "check failed"
                    )
                }

                CheckResult.UpToDate -> {
                    failedPending?.let {
                        checkedThisProcess = false
                        return@withContext ApplyResult.Failed(it.reason)
                    }
                    return@withContext ApplyResult.UpToDate
                }

                is CheckResult.UpdateAvailable -> check
            }
        } finally {
            updateInFlight.unlock()
        }

        failedPending?.let { pending ->
            if (available.remote.version <= (ContentAssets.parseVersion(pending.tag) ?: Int.MAX_VALUE)) {
                // Do not spend bandwidth downloading the exact package already
                // present locally. Leave its receipt intact and retry the
                // idempotent atomic merge on the next launch/manual check.
                checkedThisProcess = false
                return@withContext ApplyResult.Failed(pending.reason)
            }
        }

        val install: suspend () -> Boolean = { installLocked(context, syncManager, available.remote) }

        // On an unmetered connection the download costs the user nothing, so it
        // runs silently -- there is no decision worth interrupting them for. On
        // a metered one it could cost real money, so it is offered instead of
        // assumed. A user who tapped "Card Data" asked a question and deserves
        // an answer, so the manual path always surfaces the confirmation card
        // rather than installing behind their back.
        if (isUnmetered(context) && !offerAlways) {
            Log.i(TAG, "Installing ${available.remote.tag} silently (was v${available.installedVersion})")
            return@withContext if (install()) {
                ApplyResult.Applied(available.remote.tag)
            } else {
                // Nothing was promoted, so the previous content is still live.
                // The next launch retries.
                ApplyResult.Failed("silent install failed")
            }
        }

        Log.i(TAG, "${available.remote.tag} available on a metered connection; offering to user")
        val size = httpClient(followRedirects = true).use { totalSize(it, available.remote.tag) }
        ContentUpdatePrompt.offer(
            ContentUpdatePrompt.Pending(available.remote.tag, size),
            install,
        )
        ApplyResult.UpToDate
    }

    /**
     * Downloads a release, proves this build can read it, and only then makes
     * it live.
     *
     * The order is the whole point. Promoting first and merging afterwards --
     * which is what this used to do -- means a release built against a newer
     * database schema gets committed, throws inside the merge, and leaves the
     * embedding gallery loading the new index over the old catalog on every
     * launch from then on, with the exception swallowed and nothing surfaced to
     * the user. Merging first turns that into a clean no-op: the download is
     * retained as a local pending recovery source and the content already
     * installed keeps running.
     *
     * Holds [updateInFlight] for the whole transfer so a concurrent pass cannot
     * clear staging underneath it.
     */
    private suspend fun installLocked(
        context: Context,
        syncManager: DatabaseSyncManager,
        remote: RemoteContent,
    ): Boolean {
        if (!updateInFlight.tryLock()) {
            Log.w(TAG, "Another content update is running; not starting ${remote.tag}")
            return false
        }
        val dir = ContentAssets.tagDir(context, remote.tag)
        try {
            val staged = download(context, remote) { done, total ->
                ContentUpdatePrompt.reportProgress(done, total)
            }
            if (!staged) {
                // Nothing was promoted, so allow another attempt without
                // needing a fresh cold start.
                checkedThisProcess = false
                return false
            }

            if (!ContentAssets.markPending(context, remote.tag)) {
                Log.e(TAG, "${remote.tag} could not be recorded for crash-safe installation")
                dir.deleteRecursively()
                checkedThisProcess = false
                return false
            }

            if (!applyPendingLocked(context, syncManager, remote.tag, remote.version)) {
                checkedThisProcess = false
                return false
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Install failed for ${remote.tag}", e)
            // Once pending, the complete local trio is the recovery source and
            // must survive process/network failure. A pre-receipt directory is
            // safe to discard.
            if (ContentAssets.pendingTag(context) != remote.tag) {
                runCatching { dir.deleteRecursively() }
            }
            checkedThisProcess = false
            return false
        } finally {
            updateInFlight.unlock()
        }
    }

    sealed interface LocalPackageResult {
        data object Applied : LocalPackageResult
        data object Busy : LocalPackageResult
        data object Superseded : LocalPackageResult
        data class Rejected(val reason: String) : LocalPackageResult
    }

    /**
     * Installs a package that arrived by some transport other than the network --
     * currently Miror Link's peer relay.
     *
     * The caller is responsible only for producing a directory whose three files have
     * already been checked against their declared names, lengths, and SHA-256 digests.
     * Everything after that is the existing install sequence, unchanged: promote into the
     * tag directory, record the crash-safe pending receipt, validate the gallery, merge
     * through [DatabaseSyncManager.syncFromFile], activate atomically, record the
     * version, reload the model, and clear the receipt.
     *
     * The lock is taken **here** rather than around the caller's transfer. A peer
     * transfer can run for minutes, and [updateInFlight] also gates the startup merge, so
     * holding it across the wire would stall unrelated content work for the duration. The
     * version is therefore rechecked under the lock: the device may have updated over the
     * network while the peer transfer was still running.
     */
    suspend fun installVerifiedPackage(
        context: Context,
        syncManager: DatabaseSyncManager,
        verifiedDir: File,
        tag: String,
        version: Int,
    ): LocalPackageResult = withContext(Dispatchers.IO) {
        // Validate the peer-controlled tag before using it as a filesystem child. The
        // later pending-marker validation is intentionally not enough: tagDir() is used
        // (and deleted) first, so a malformed path must never reach that point.
        if (tag != "content-v$version" || ContentAssets.parseVersion(tag) != version) {
            Log.w(TAG, "Refusing non-canonical peer content tag $tag for version $version")
            return@withContext LocalPackageResult.Rejected("The received update had an invalid version.")
        }
        // Absolute bound on how far ahead a peer may claim to be. The signature already
        // binds the version, so this is defence in depth -- but it is what stops the
        // whole class of attack where a relative "newer than mine" comparison is turned
        // into a permanent floor by an absurd number. Releases increment by one; a peer
        // more than a few ahead is not plausible.
        val jump = version - activeVersion(context)
        if (jump > MAX_PEER_VERSION_JUMP) {
            Log.w(TAG, "Refusing implausible peer content version $version (jump of $jump)")
            return@withContext LocalPackageResult.Rejected("The received update had an invalid version.")
        }
        if (!updateInFlight.tryLock()) {
            Log.i(TAG, "A content update is already running; not installing $tag from a peer")
            return@withContext LocalPackageResult.Busy
        }
        try {
            if (version <= activeVersion(context)) {
                Log.i(TAG, "$tag is no longer newer than the active content; discarding")
                return@withContext LocalPackageResult.Superseded
            }
            if (!ContentAssets.isComplete(verifiedDir)) {
                return@withContext LocalPackageResult.Rejected("The received update was incomplete.")
            }

            // Provenance, checked before these bytes are allowed anywhere near the live
            // content tree. The digests the peer sent prove only that the transfer was
            // intact; this proves the package came from Miror's release pipeline. Checked
            // here rather than only in applyPendingLocked so unverified peer bytes never
            // enter content/ at all.
            if (!ContentSignature.verify(verifiedDir, version)) {
                Log.w(TAG, "Peer package for $tag failed signature verification")
                return@withContext LocalPackageResult.Rejected(
                    "That card data could not be verified as genuine and was not installed.",
                )
            }

            // Promotion into the tag directory is what the existing installer expects:
            // markPending and applyPendingLocked both read from ContentAssets.tagDir.
            val installDir = ContentAssets.tagDir(context, tag)
            installDir.deleteRecursively()
            if (!verifiedDir.renameTo(installDir)) {
                verifiedDir.copyRecursively(installDir, overwrite = true)
                verifiedDir.deleteRecursively()
            }
            if (!ContentAssets.isComplete(installDir)) {
                installDir.deleteRecursively()
                return@withContext LocalPackageResult.Rejected("The update could not be staged.")
            }

            if (!ContentAssets.markPending(context, tag)) {
                installDir.deleteRecursively()
                return@withContext LocalPackageResult.Rejected("The update could not be recorded.")
            }

            if (!applyPendingLocked(context, syncManager, tag, version)) {
                // Deliberately leaves the pending receipt and files in place: they are now
                // the local recovery source, and startup retries the idempotent merge. The
                // previously active content is still live either way.
                return@withContext LocalPackageResult.Rejected(
                    "Miror could not use that card data. Your existing data is unchanged.",
                )
            }

            Log.i(TAG, "Installed $tag received from a peer")
            LocalPackageResult.Applied
        } catch (e: Exception) {
            Log.e(TAG, "Peer package install failed for $tag", e)
            LocalPackageResult.Rejected("The update could not be installed.")
        } finally {
            updateInFlight.unlock()
        }
    }

    /**
     * Completes a previously downloaded install without requiring a network.
     * The database upsert is idempotent, so replay is safe after process death
     * at any point before the pending receipt is cleared.
     */
    private suspend fun recoverPendingLocked(
        context: Context,
        syncManager: DatabaseSyncManager,
    ): PendingRecovery {
        val tag = ContentAssets.pendingTag(context) ?: return PendingRecovery.None
        val version = ContentAssets.parseVersion(tag)
            ?: return PendingRecovery.Failed(tag, "pending content tag is invalid")

        // Marker activation happened and the process died before acknowledging
        // it. The DB transaction necessarily succeeded first, so only finish
        // the durable bookkeeping and reload.
        if (ContentAssets.installedTag(context) == tag) {
            syncManager.recordAssetVersion(version)
            com.selenite.scanner.ml.MLManager.onContentUpdated()
            ContentAssets.finishPending(context, tag)
            Log.i(TAG, "Recovered already-active content $tag")
            return PendingRecovery.Applied(tag)
        }

        return if (applyPendingLocked(context, syncManager, tag, version)) {
            PendingRecovery.Applied(tag)
        } else {
            PendingRecovery.Failed(tag, "pending content $tag could not be applied; it will retry")
        }
    }

    /**
     * Applies one complete local release. Card rows remain in their purposeful
     * 500-row chunks, while [DatabaseSyncManager] commits all chunks in one
     * transaction. The content marker and version advance only after that
     * transaction succeeds.
     */
    private suspend fun applyPendingLocked(
        context: Context,
        syncManager: DatabaseSyncManager,
        tag: String,
        version: Int,
    ): Boolean {
        val dir = ContentAssets.tagDir(context, tag)
        // The universal provenance gate. Every path that activates content converges
        // here -- network download, peer relay, and crash recovery -- so this is the one
        // place that guarantees nothing unsigned is ever merged, and it sits before
        // syncFromFile hands cards.db to SQLite.
        //
        // Applied to the network path too, with no grandfather branch. No unsigned
        // package has ever shipped, so "unsigned is acceptable" would be a permanently
        // weaker path bought for no compatibility at all.
        if (!ContentSignature.verify(dir, version)) {
            Log.e(TAG, "$tag failed signature verification; keeping current content")
            return false
        }
        val galleryIds = validatePendingGallery(context, dir)
        if (galleryIds == null) {
            Log.e(TAG, "$tag has an invalid or non-monotonic embedding gallery; keeping current content")
            return false
        }
        if (!syncManager.syncFromFile(File(dir, ContentAssets.CARDS_DB), galleryIds)) {
            Log.e(TAG, "$tag could not be merged atomically; keeping current content and pending files")
            return false
        }
        if (!ContentAssets.commit(context, tag)) {
            Log.e(TAG, "$tag database merged, but activation failed; retaining it for local retry")
            return false
        }

        // The content is live only now, so this is the first honest moment to
        // acknowledge its version, request the serialized gallery reload, and
        // clear the recovery receipt.
        syncManager.recordAssetVersion(version)
        com.selenite.scanner.ml.MLManager.onContentUpdated()
        ContentAssets.finishPending(context, tag)
        Log.i(TAG, "Installed content $tag")
        return true
    }

    /**
     * Proves the independently downloaded gallery is structurally compatible
     * with both this runtime and the gallery already on the device.
     *
     * OTA indexes are append-only by row. PrintLens family members reference
     * global rows shipped in the APK, so silently reordering an existing ID
     * would rerank against the wrong card even though the file sizes still
     * looked valid. Requiring the active ID list as an exact prefix preserves
     * those row positions while still allowing every new set to append.
     */
    private fun validatePendingGallery(context: Context, dir: File): Set<String>? = runCatching {
        val idsFile = File(dir, ContentAssets.INDEX_IDS)
        val binFile = File(dir, ContentAssets.INDEX_BIN)
        // GalleryIds drops the trailing release-signature line, which is metadata rather
        // than a row. Counting it would put the expected byte count below out by one
        // gallery row and reject a valid release.
        val candidateIds = idsFile.bufferedReader().useLines { lines ->
            GalleryIds.parse(lines)
        }
        if (candidateIds.isEmpty()) {
            error("Candidate gallery has no IDs")
        }
        val uniqueIds = candidateIds.toHashSet()
        if (uniqueIds.size != candidateIds.size) {
            error("Candidate gallery contains duplicate IDs")
        }
        val exactIds = candidateIds.map(::exactCardIdFromGalleryId)
        if (exactIds.toHashSet().size != exactIds.size) {
            error("Candidate gallery contains duplicate exact card IDs")
        }

        val expectedBytes =
            candidateIds.size.toLong() * GLOBAL_EMBEDDING_DIM.toLong() * FP16_BYTES
        if (!binFile.isFile || binFile.length() != expectedBytes) {
            error(
                "Candidate gallery byte count is ${binFile.length()}, expected $expectedBytes " +
                    "for ${candidateIds.size} IDs",
            )
        }

        val activeIds = ContentAssets.open(context, ContentAssets.INDEX_IDS)
            .bufferedReader()
            .useLines { lines -> GalleryIds.parse(lines) }
        if (candidateIds.size < activeIds.size) {
            error(
                "Candidate gallery shrank from ${activeIds.size} to ${candidateIds.size} rows",
            )
        }
        val firstChangedRow = activeIds.indices.firstOrNull { candidateIds[it] != activeIds[it] }
        if (firstChangedRow != null) {
            error(
                "Candidate gallery changed existing row $firstChangedRow " +
                    "from ${activeIds[firstChangedRow]} to ${candidateIds[firstChangedRow]}",
            )
        }

        // A frozen baseline contains two known gallery-only legacy rows. The
        // producer verifier quarantines those exact inherited rows, and the
        // prefix gate above guarantees they cannot change. Only newly appended
        // rows need to be proved present in the candidate cards.db.
        exactIds.drop(activeIds.size).toSet()
    }.onFailure {
        Log.e(TAG, "Pending gallery validation failed", it)
    }.getOrNull()

    private fun exactCardIdFromGalleryId(galleryId: String): String {
        val normalized = galleryId.replace('\\', '/')
        val firstSlash = normalized.indexOf('/')
        if (firstSlash <= 0) error("Gallery ID has no set directory: $galleryId")
        val setId = normalized.substring(0, firstSlash).substringBefore('_')
        if (setId.isBlank()) error("Gallery ID has no set ID: $galleryId")
        val filename = normalized.substringAfterLast('/')
        val stem = filename.substringBeforeLast('.', missingDelimiterValue = "")
        val marker = "_$setId-"
        val markerIndex = stem.lowercase().lastIndexOf(marker.lowercase())
        if (markerIndex < 0) {
            error("Gallery filename has no canonical exact-ID suffix: $galleryId")
        }
        return stem.substring(markerIndex + 1).takeIf(String::isNotBlank)
            ?: error("Gallery filename has a blank exact-ID suffix: $galleryId")
    }

    /**
     * Runs a check immediately, bypassing the once-per-process guard.
     *
     * Exists because content can be published a minute after a user's cold
     * start, and without this they would wait for the next one.
     */
    suspend fun forceCheck(context: Context, syncManager: DatabaseSyncManager): ApplyResult {
        checkedThisProcess = false
        val result = autoUpdateOnStartup(context, syncManager, offerAlways = true)
        // A failed check must not read as "up to date" -- offline is a
        // different answer to the user's question than nothing-new.
        if (result is ApplyResult.Failed) {
            ContentUpdatePrompt.reportCheckFailed()
        }
        return result
    }

    /**
     * True on a connection the user is not paying per byte for. Anything we
     * cannot read is treated as metered, so an unknown network prompts rather
     * than silently spending someone's data.
     */
    private fun isUnmetered(context: Context): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (e: Exception) {
        Log.w(TAG, "Could not read network state; treating as metered", e)
        false
    }

    sealed interface ApplyResult {
        data class Applied(val tag: String) : ApplyResult
        data object UpToDate : ApplyResult
        data class Failed(val reason: String) : ApplyResult
    }

    /** Best-effort total byte count, used only to drive a progress bar. */
    private suspend fun totalSize(client: HttpClient, tag: String): Long = try {
        var sum = 0L
        for (name in ContentAssets.REQUIRED_FILES) {
            val length = client.head("$DOWNLOAD_BASE/$tag/$name")
                .headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
            sum += length
        }
        if (sum > 0) sum else -1L
    } catch (e: Exception) {
        Log.w(TAG, "Could not determine total download size", e)
        -1L
    }
}
