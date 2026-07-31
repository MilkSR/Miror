package com.selenite.scanner.link

import com.selenite.scanner.share.SharedCollection

enum class MirorLinkPhase {
    Idle,
    PermissionSetup,
    Discovering,
    SelectingPeer,
    Connecting,
    ExchangingHello,
    ExchangingSnapshots,
    Viewing,
    Complete,
    Cancelled,

    /**
     * Nobody appeared before the discovery deadline. Distinct from [Failed] because
     * nothing went wrong -- the radios were simply released rather than left advertising,
     * and the user can just try again.
     */
    TimedOut,
    Failed,
}

enum class MirorLinkContentPhase {
    None,
    Offered,
    Receiving,
    Applying,
    Applied,
    Declined,
    Failed,
}

/** How a shared collection reached this device. Drives viewer copy and the refresh handoff. */
enum class SharedCollectionSource {
    Sigil,
    Link,
}

enum class SharedCollectionScope {
    Full,
    Selected,
}

/**
 * A received collection plus the raw manifest that produced it.
 *
 * The raw bytes are retained deliberately: after a peer content update installs, Link
 * re-resolves *these* bytes in place so previously unknown cards appear without
 * reconnecting and without closing the viewer. Sigil's existing refresh handoff --
 * which dismisses the overlay and navigates to card-data options -- is left alone.
 */
data class MirorLinkRemoteCollection(
    val collection: SharedCollection,
    internal val manifestBytes: ByteArray,
    val peerNickname: String,
    val scope: SharedCollectionScope,
) {
    override fun equals(other: Any?): Boolean =
        other is MirorLinkRemoteCollection && collection == other.collection &&
            manifestBytes.contentEquals(other.manifestBytes) &&
            peerNickname == other.peerNickname && scope == other.scope

    override fun hashCode(): Int {
        var result = collection.hashCode()
        result = 31 * result + manifestBytes.contentHashCode()
        result = 31 * result + peerNickname.hashCode()
        result = 31 * result + scope.hashCode()
        return result
    }
}

/** One selectable peer, shown only when automatic selection could not decide. */
data class MirorLinkPeerChoice(
    val key: String,
    val nickname: String,
    /** Session-only differentiator appended when two peers advertise the same nickname. */
    val differentiator: String?,
)

data class MirorLinkContentOffer(
    val peerNickname: String,
    val offeredVersion: Int,
    val localVersion: Int,
    /** Absent when the donor could not determine it cheaply; the prompt says so. */
    val totalSizeBytes: Long?,
)

data class MirorLinkUiState(
    val phase: MirorLinkPhase = MirorLinkPhase.Idle,
    val availability: MirorLinkAvailabilityUi = MirorLinkAvailabilityUi.Unknown,
    val nickname: String = "",
    val peerNickname: String? = null,
    val candidates: List<MirorLinkPeerChoice> = emptyList(),
    val showChooser: Boolean = false,
    val sendProgress: Float = 0f,
    val receiveProgress: Float = 0f,
    val remote: MirorLinkRemoteCollection? = null,
    val contentPhase: MirorLinkContentPhase = MirorLinkContentPhase.None,
    val contentOffer: MirorLinkContentOffer? = null,
    val contentProgress: Float = 0f,
    val contentMessage: String? = null,
    /**
     * Peer photos received so far, card id to a local cache path. Populated lazily as the
     * viewer opens cards, never in bulk, and dropped when the session ends.
     */
    val peerPhotos: Map<String, String> = emptyMap(),
    /** Cards whose photo has been asked for and not yet arrived; drives the spinner. */
    val pendingPhotoCardIds: Set<String> = emptySet(),
    /** Null until HELLO; false when the peer opted out of sharing photos. */
    val peerSharesPhotos: Boolean? = null,

    /**
     * A short explanation of why the radio session ended while its collection is still
     * on screen. Currently only the viewing idle timeout sets it.
     */
    val sessionNotice: String? = null,
    val failure: String? = null,
    /** True when the peer requires a newer Miror; the UI shows an update prompt. */
    val updateRequired: Boolean = false,
    val isReResolving: Boolean = false,
) {
    val isActive: Boolean
        get() = phase != MirorLinkPhase.Idle &&
            phase != MirorLinkPhase.Complete &&
            phase != MirorLinkPhase.Cancelled &&
            // The radios are already released once discovery times out, so a timed-out
            // session is over. Reporting it as active would offer to stop something that
            // has already stopped -- the same confusion End Link used to cause.
            phase != MirorLinkPhase.TimedOut &&
            phase != MirorLinkPhase.Failed
}

enum class MirorLinkAvailabilityUi {
    Unknown,
    Ready,
    NeedsPermissions,
    RadiosOff,

    /** No Google Play services, or the platform cannot run Nearby. Offer Sigils. */
    Unsupported,
}
