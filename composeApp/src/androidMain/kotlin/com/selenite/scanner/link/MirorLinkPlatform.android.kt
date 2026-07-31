package com.selenite.scanner.link

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.selenite.scanner.util.AndroidContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

private val secureRandom by lazy { SecureRandom() }

actual fun mirorLinkRandomBytes(count: Int): ByteArray =
    ByteArray(count).also { secureRandom.nextBytes(it) }

internal actual fun mirorLinkLog(message: String) {
    android.util.Log.i("MirorLink", message)
}

/**
 * Application-scoped foreground observer.
 *
 * Counts *started* activities rather than reacting to `onPause`. That distinction is
 * load-bearing: Android's runtime permission dialog pauses the hosting activity
 * without stopping it, so treating a pause as "backgrounded" would tear the session
 * down at exactly the moment Link asks for the Nearby permissions it needs. A screen
 * lock or a genuine task switch does call `onStop`, which is what should end a session.
 *
 * Holds no `Activity` reference -- only a count -- so it cannot leak one across the
 * configuration changes that recreate `MainActivity`.
 */
private class AndroidMirorLinkLifecycle(application: Application?) : MirorLinkLifecycle {
    private val _events = MutableSharedFlow<MirorLinkLifecycleEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val startedActivities = AtomicInteger(0)

    override val events: Flow<MirorLinkLifecycleEvent> = _events.asSharedFlow()

    override val isForegrounded: Boolean get() = startedActivities.get() > 0

    init {
        application?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (startedActivities.incrementAndGet() == 1) {
                        _events.tryEmit(MirorLinkLifecycleEvent.Foregrounded)
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    if (startedActivities.decrementAndGet() <= 0) {
                        startedActivities.set(0)
                        // A configuration change can stop the old Activity before the new
                        // one starts. It is still the same foreground task and the
                        // process-scoped Link must survive that handoff.
                        if (!activity.isChangingConfigurations) {
                            _events.tryEmit(MirorLinkLifecycleEvent.Backgrounded)
                        }
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}

actual fun createMirorLinkLifecycle(): MirorLinkLifecycle =
    AndroidMirorLinkLifecycle(AndroidContext?.applicationContext as? Application)

internal actual fun createMirorLinkTransport(): MirorLinkTransport? =
    AndroidContext?.applicationContext?.let { AndroidMirorLinkTransport(it) }

internal actual fun createMirorLinkContentGateway(): MirorLinkContentGateway? =
    AndroidContext?.applicationContext?.let { AndroidMirorLinkContentGateway(it) }
