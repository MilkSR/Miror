package com.selenite.scanner.link

internal data class MirorLinkContentCapability(
    /** This device can offer its active content to a peer. */
    val canServe: Boolean,
    /**
     * This device can install content received from a peer. False on iOS until its
     * writable content storage, atomic activation, and gallery reload reach parity.
     */
    val canInstall: Boolean,
    val activeVersion: Int,
)

/**
 * A coherent, private copy of the donor's active content trio, taken **after** the
 * receiver accepts.
 *
 * Copying rather than serving the live files is what keeps the snapshot coherent: the
 * donor's active content can be switched by a concurrent update at any moment, and
 * `ContentAssets.finishPending` prunes superseded tag directories. A private copy
 * means activation mid-transfer cannot swap or delete the bytes being streamed, and
 * the donor never has to hold the updater mutex to prevent that.
 */
internal interface MirorLinkOutgoingContent {
    val tag: String
    val version: Int

    /** Exact sizes, known because the copy pass measured them. */
    val files: List<MirorLinkContentFile>

    /** SHA-256 computed during the same copy pass, so the package is read once. */
    fun digest(fileIndex: Int): ByteArray

    fun read(fileIndex: Int, offset: Long, maxBytes: Int): ByteArray

    fun close()
}

/**
 * Receiver-side staging in a Link-owned directory, deliberately **outside**
 * `ContentAssets.stagingDir()`.
 *
 * `ContentAssets.clearStaging` deletes that whole `.staging` tree and runs from both
 * `ContentUpdateManager.download` and `autoUpdateOnStartup`. Sharing it would make an
 * ordinary network update pass destroy an in-flight Link transfer, or force Link to
 * hold the updater mutex for the entire wireless transfer to prevent it.
 */
internal interface MirorLinkContentStaging {
    fun beginFile(index: Int, name: String, sizeBytes: Long)

    fun write(index: Int, bytes: ByteArray)

    /** Verifies the streamed length and digest for one file. */
    fun finishFile(index: Int, expectedBytes: Long, expectedSha256: ByteArray): Boolean

    /** Deletes everything this session wrote. Safe to call more than once. */
    fun abandon()
}

internal sealed interface MirorLinkContentInstallResult {
    data object Applied : MirorLinkContentInstallResult

    /** Existing validation, merge, or activation refused it. Old content stays live. */
    data class Rejected(val reason: String) : MirorLinkContentInstallResult

    /** Another content update holds the updater mutex. */
    data object Busy : MirorLinkContentInstallResult

    /** The receiver's active version advanced past the offer while the transfer ran. */
    data object Superseded : MirorLinkContentInstallResult
}

/**
 * Platform bridge for peer content relay. Null on platforms without one, which is how
 * iOS reports `contentInstall = false` until updater parity lands.
 */
internal interface MirorLinkContentGateway {
    fun capability(): MirorLinkContentCapability

    /**
     * Cheap descriptor for `HELLO`/`CONTENT_OFFER`. Must not read or hash the package:
     * entering Link, making an offer, and declining an offer all have to stay free.
     * Sizes are omitted when they are not cheaply available -- notably for a
     * bundle-only donor, whose `cards.db` is Deflate-compressed inside the APK.
     */
    suspend fun describeActive(): ContentOfferMessage?

    suspend fun openOutgoing(): MirorLinkOutgoingContent?

    fun beginStaging(sessionKey: String): MirorLinkContentStaging

    /**
     * Hands a fully verified trio to the existing staged-package installer. The
     * implementation acquires the existing update mutex here -- not earlier -- rechecks
     * that the offer is still newer, promotes the directory into
     * `ContentAssets.tagDir(tag)`, and then runs the existing
     * markPending/validate/sync/commit/reload/recovery sequence unchanged.
     */
    suspend fun installVerified(
        staging: MirorLinkContentStaging,
        tag: String,
        version: Int,
    ): MirorLinkContentInstallResult

    /** Reclaims Link staging left behind by an interrupted session. */
    fun cleanupAbandonedStaging()
}
