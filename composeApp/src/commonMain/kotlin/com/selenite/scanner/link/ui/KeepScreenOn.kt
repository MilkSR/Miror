package com.selenite.scanner.link.ui

import androidx.compose.runtime.Composable

/**
 * Holds automatic screen sleep off while [enabled].
 *
 * Miror Link is foreground-only and has no wake lock, no foreground service and no
 * background mode -- so if the screen sleeps, the session ends. That is correct when the
 * user walks away and wrong when two people are looking at a collection together and
 * simply not touching anything.
 *
 * Deliberately scoped to a *committed* session. Pre-warming does not qualify: nothing has
 * been shared and the user has not asked for anything to stay open. Locking the phone or
 * leaving the app still ends Link immediately -- this suppresses the automatic timeout,
 * not the user's own decision.
 *
 * The platform setting is restored on dispose, so every exit path -- teardown, timeout,
 * dismissal, failure -- puts it back without needing to know it existed.
 */
@Composable
expect fun KeepScreenOn(enabled: Boolean)
