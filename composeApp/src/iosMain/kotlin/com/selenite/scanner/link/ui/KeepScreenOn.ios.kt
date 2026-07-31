package com.selenite.scanner.link.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    DisposableEffect(enabled) {
        UIApplication.sharedApplication.idleTimerDisabled = enabled
        onDispose {
            // Always restored, so a teardown, timeout or failure cannot leave the idle
            // timer suppressed for the rest of the app's life.
            UIApplication.sharedApplication.idleTimerDisabled = false
        }
    }
}
