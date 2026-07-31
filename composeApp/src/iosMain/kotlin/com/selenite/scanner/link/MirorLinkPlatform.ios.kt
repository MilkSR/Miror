package com.selenite.scanner.link

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.posix.arc4random_buf

@OptIn(ExperimentalForeignApi::class)
actual fun mirorLinkRandomBytes(count: Int): ByteArray {
    require(count > 0) { "Link random byte count must be positive" }
    val bytes = ByteArray(count)
    bytes.usePinned { pinned ->
        arc4random_buf(pinned.addressOf(0), count.toULong())
    }
    return bytes
}

/**
 * `UIApplication` foreground observer.
 *
 * `WillResignActive` maps to [MirorLinkLifecycleEvent.Interrupted] rather than being
 * ignored: on iOS that is what an incoming call or the app switcher looks like, and
 * Link should stop its radios for those exactly as it does for a true background
 * transition. No background modes are requested anywhere.
 */
private class IosMirorLinkLifecycle : MirorLinkLifecycle {
    private val _events = MutableSharedFlow<MirorLinkLifecycleEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: Flow<MirorLinkLifecycleEvent> = _events.asSharedFlow()

    override val isForegrounded: Boolean
        get() = UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive

    init {
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue

        center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = queue,
        ) { _ -> _events.tryEmit(MirorLinkLifecycleEvent.Backgrounded) }

        center.addObserverForName(
            name = UIApplicationWillResignActiveNotification,
            `object` = null,
            queue = queue,
        ) { _ -> _events.tryEmit(MirorLinkLifecycleEvent.Interrupted) }

        center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = queue,
        ) { _ -> _events.tryEmit(MirorLinkLifecycleEvent.Foregrounded) }

        center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = queue,
        ) { _ -> _events.tryEmit(MirorLinkLifecycleEvent.Foregrounded) }
    }
}

internal actual fun mirorLinkLog(message: String) {
    platform.Foundation.NSLog("MirorLink: %@", message)
}

actual fun createMirorLinkLifecycle(): MirorLinkLifecycle = IosMirorLinkLifecycle()

internal actual fun createMirorLinkTransport(): MirorLinkTransport? =
    IosMirorLinkTransport.createIfBridgeAvailable()

/**
 * Null on iOS, which is how `contentInstall = false` is advertised to peers.
 *
 * iOS has no writable content storage at all today: `DatabaseSyncManager` reads
 * `cards.db` straight out of `NSBundle`, and there is no iOS `ContentAssets` or
 * `ContentUpdateManager`. Peer content install stays off until that parity work --
 * writable versioned storage, atomic activation, gallery reload, startup recovery, and
 * fallback -- exists and has been device-tested on its own.
 */
internal actual fun createMirorLinkContentGateway(): MirorLinkContentGateway? = null

/**
 * Null on iOS for now, so iOS neither offers nor requests card photos and both sides fall
 * back to catalog images. Implementing it needs UIImage downscaling and a session cache
 * that can be verified on real hardware; shipping an unverified version would be worse
 * than not advertising the capability at all.
 */
internal actual fun createMirorLinkPhotoCodec(): MirorLinkPhotoCodec? = null
