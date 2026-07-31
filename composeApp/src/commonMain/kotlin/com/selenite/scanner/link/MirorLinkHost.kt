package com.selenite.scanner.link

import com.selenite.scanner.data.OfflineCardRepository
import com.selenite.scanner.data.SettingsManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Null on platforms that cannot run Nearby Connections at all. */
internal expect fun createMirorLinkTransport(): MirorLinkTransport?

/** Null where peer content install is not supported -- currently everywhere but Android. */
internal expect fun createMirorLinkContentGateway(): MirorLinkContentGateway?

/**
 * Process-scoped owner of the single Link coordinator.
 *
 * The coordinator must outlive composition. `MainActivity` declares no `configChanges`,
 * so a rotation or the automatic theme change at sunset destroys and recreates it --
 * the same hazard that silently killed content downloads until `ContentUpdateManager`
 * moved its work to an application scope. A coordinator remembered in composition would
 * be rebuilt by that recreation, leaving the previous one's radios running.
 */
object MirorLinkHost {

    /**
     * The backstop, not the fix.
     *
     * A `SupervisorJob` keeps one failed child from cancelling its siblings, but it does
     * nothing about the child's exception itself: with no handler in the context, kotlinx
     * hands it to the thread's uncaught-exception handler, which on Android is the one that
     * kills the process. A dropped radio while sending a frame therefore used to be able to
     * take the whole app down.
     *
     * The real fix is that no Link coroutine throws for a send failure any more -- see
     * `MirorLinkCoordinator.sendLocked`. This is here so that if some future path does
     * throw, the outcome is a session that ends and cleans up rather than a crash.
     *
     * The exception's own message is deliberately never logged or surfaced: it can be
     * platform text containing endpoint identifiers, and the coordinator already owns the
     * trusted user-facing copy.
     */
    private val crashBackstop = CoroutineExceptionHandler { _, throwable ->
        if (throwable is kotlinx.coroutines.CancellationException) return@CoroutineExceptionHandler
        mirorLinkLog("a Link coroutine failed unexpectedly (${throwable::class.simpleName})")
        coordinator?.recoverFromUnexpectedFailure()
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashBackstop)

    private var coordinator: MirorLinkCoordinator? = null
    private var lifecycle: MirorLinkLifecycle? = null

    /** True when this platform has a Link transport at all, regardless of runtime state. */
    val isSupportedPlatform: Boolean by lazy { createMirorLinkTransport() != null }

    fun coordinator(
        repository: OfflineCardRepository,
        settingsManager: SettingsManager,
        appBuild: String? = null,
    ): MirorLinkCoordinator? {
        coordinator?.let { return it }
        val transport = createMirorLinkTransport() ?: return null
        val observed = lifecycle ?: createMirorLinkLifecycle().also { lifecycle = it }
        return MirorLinkCoordinator(
            transport = transport,
            snapshots = MirorLinkSnapshotService(repository),
            lifecycle = observed,
            contentGateway = createMirorLinkContentGateway(),
            scope = appScope,
            nicknameProvider = { settingsManager.getLinkNickname().ifBlank { "Miror user" } },
            appBuild = appBuild,
            photoCodec = createMirorLinkPhotoCodec(),
            sharePhotos = { settingsManager.shouldShareLinkPhotos() },
            receivePhotos = { settingsManager.shouldReceiveLinkPhotos() },
            // Recorded the moment a session gets past the availability/permission gate,
            // which is exactly when the user has seen and answered the permission prompt
            // behind an explicit Miror Link tap. Pre-warming is unlocked from then on.
            onLinkStarted = { settingsManager.setHasUsedMirorLink(true) },
            proximitySource = createMirorLinkProximitySource(),
        ).also { coordinator = it }
    }
}
