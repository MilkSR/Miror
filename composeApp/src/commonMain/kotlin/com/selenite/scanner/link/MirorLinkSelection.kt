package com.selenite.scanner.link

/**
 * Pure peer-selection rules.
 *
 * Deliberately free of coroutines, transports, and clocks so every rule below can be
 * asserted directly. The coordinator supplies the inputs and performs the effects.
 */
internal object MirorLinkSelection {

    /** A discovered peer, keyed by its ephemeral nonce rather than by endpoint id. */
    data class Candidate(
        val endpointId: String,
        val info: MirorLinkEndpointInfo,
    ) {
        val nonceKey: String get() = info.nonce.toHexLowercase()
    }

    sealed interface Outcome {
        data class Selected(val candidate: Candidate) : Outcome
        data class NeedsChooser(val candidates: List<Candidate>) : Outcome
        data object None : Outcome
    }

    /**
     * A peer is only a candidate when its protocol range overlaps ours. Filtering here
     * rather than at connection time means an incompatible peer never destabilises the
     * stability window or triggers a chooser the user cannot act on usefully.
     */
    fun compatible(candidates: Collection<Candidate>): List<Candidate> =
        candidates.filter {
            MirorLinkProtocol.protocolRangesOverlap(it.info.protocolMin, it.info.protocolMax) &&
                it.info.supportsRawManifest
        }.sortedBy { it.nonceKey }

    /**
     * Exactly one compatible peer links automatically. More than one goes to proximity
     * ranking, and anything short of a confident ranking goes to the nickname chooser.
     * RSSI is a convenience signal, never proof, so uncertainty always degrades to the
     * chooser rather than to a guess.
     */
    fun choose(
        candidates: Collection<Candidate>,
        proximity: MirorLinkProximityDecision,
    ): Outcome {
        val compatible = compatible(candidates)
        return when {
            compatible.isEmpty() -> Outcome.None
            compatible.size == 1 -> Outcome.Selected(compatible.single())
            proximity is MirorLinkProximityDecision.Confident -> {
                val match = compatible.firstOrNull { it.nonceKey == proximity.nonceKey }
                if (match != null) Outcome.Selected(match) else Outcome.NeedsChooser(compatible)
            }
            else -> Outcome.NeedsChooser(compatible)
        }
    }

    /**
     * Deterministic initiator election.
     *
     * Both phones advertise and discover, so without a rule both would request a
     * connection at the same instant and the SDK would see two half-open attempts. The
     * lexicographically lower nonce initiates; the other waits. This assigns no
     * permanent sender/receiver role -- it is decided fresh from the two random nonces
     * of this session alone.
     */
    fun shouldInitiate(localNonce: ByteArray, peerNonce: ByteArray): Boolean =
        compareNonces(localNonce, peerNonce) < 0

    fun compareNonces(left: ByteArray, right: ByteArray): Int {
        val size = minOf(left.size, right.size)
        for (index in 0 until size) {
            val a = left[index].toInt() and 0xFF
            val b = right[index].toInt() and 0xFF
            if (a != b) return a.compareTo(b)
        }
        return left.size.compareTo(right.size)
    }

    /**
     * Mutual-selection check. An inbound connection is only completed when the peer
     * that is calling is the peer this phone chose, which is what stops Miror from
     * sharing with a phone that is actually trying to link to somebody else.
     */
    fun acceptsInboundFrom(
        selectedPeerNonce: ByteArray?,
        requesterNonce: ByteArray?,
    ): InboundDecision = when {
        requesterNonce == null -> InboundDecision.Reject
        selectedPeerNonce == null -> InboundDecision.Hold
        selectedPeerNonce.contentEquals(requesterNonce) -> InboundDecision.Accept
        else -> InboundDecision.Reject
    }

    enum class InboundDecision {
        Accept,

        /**
         * Local selection is still settling. Discovery can be asymmetric by a few
         * callbacks, so the lower-nonce side can call before the higher-nonce side has
         * finished choosing. Holding briefly avoids rejecting the correct peer and
         * stranding it; the hold is bounded and resolves to Accept or Reject.
         */
        Hold,
        Reject,
    }

    /**
     * Validates a peer's `HELLO` against this session. All three conditions from the
     * plan are checked: it names this local session, it came from the peer selected
     * locally, and that peer selected this session in return.
     */
    fun validateHello(
        hello: HelloMessage,
        localNonce: ByteArray,
        selectedPeerNonce: ByteArray,
    ): HelloValidation = when {
        !hello.selectedPeerNonce.contentEquals(localNonce) ->
            HelloValidation.NotForThisSession

        !hello.localNonce.contentEquals(selectedPeerNonce) ->
            HelloValidation.WrongPeer

        !MirorLinkProtocol.protocolRangesOverlap(hello.protocolMin, hello.protocolMax) ->
            HelloValidation.IncompatibleProtocol

        !hello.supportsRawManifest -> HelloValidation.IncompatibleProtocol

        else -> HelloValidation.Ok
    }

    enum class HelloValidation { Ok, NotForThisSession, WrongPeer, IncompatibleProtocol }

    /**
     * Duplicate nicknames get a neutral, session-only differentiator. It is derived
     * from the ephemeral nonce, so it conveys nothing durable about the device.
     */
    fun toChoices(candidates: List<Candidate>): List<MirorLinkPeerChoice> {
        val nameCounts = candidates.groupingBy { it.info.nickname.lowercase() }.eachCount()
        return candidates.map { candidate ->
            val duplicated = (nameCounts[candidate.info.nickname.lowercase()] ?: 0) > 1
            MirorLinkPeerChoice(
                key = candidate.nonceKey,
                nickname = candidate.info.nickname.ifBlank { "Miror user" },
                differentiator = if (duplicated) candidate.nonceKey.take(4).uppercase() else null,
            )
        }
    }
}
