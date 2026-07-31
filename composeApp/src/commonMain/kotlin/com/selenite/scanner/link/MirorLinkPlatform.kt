package com.selenite.scanner.link

import kotlinx.coroutines.flow.Flow

/**
 * Foreground signal for Miror Link.
 *
 * Link is foreground-only by product decision, but until this existed no common
 * code in Miror could observe the process leaving the foreground -- the app's only
 * platform-event abstraction was `PlatformBackHandler`, which is back-press only.
 * Without a signal the coordinator cannot honour "backgrounding stops the radios",
 * so this is the contract that makes that rule enforceable rather than aspirational.
 */
enum class MirorLinkLifecycleEvent {
    Foregrounded,

    /** The app left the foreground: backgrounded, locked, or task-switched away. */
    Backgrounded,

    /**
     * Still foreground but no longer interactive -- an incoming call, the app
     * switcher, a system dialog. Treated exactly like [Backgrounded] by the
     * coordinator; kept distinct so platform code does not have to lie about which
     * notification it saw.
     */
    Interrupted,
}

interface MirorLinkLifecycle {
    val events: Flow<MirorLinkLifecycleEvent>

    /** True when the app is in the foreground right now. */
    val isForegrounded: Boolean
}

/**
 * Android observes application-level activity lifecycle callbacks (no retained
 * `Activity`); iOS observes `UIApplication` notifications.
 */
expect fun createMirorLinkLifecycle(): MirorLinkLifecycle

/**
 * Unpredictable bytes for session nonces and ephemeral BLE keys.
 *
 * These are not secrets and Link adds no cryptographic trust layer, but a guessable
 * nonce would let a third phone claim to be the peer that was selected, so this uses
 * the platform CSPRNG rather than [kotlin.random.Random].
 */
expect fun mirorLinkRandomBytes(count: Int): ByteArray

/**
 * Diagnostic logging for Link's state machine.
 *
 * Deliberately constrained to protocol facts -- phases, versions, and decision outcomes.
 * Never a nickname, nonce, endpoint id, or anything about a collection, which the release
 * checklist forbids from production logs.
 */
internal expect fun mirorLinkLog(message: String)
