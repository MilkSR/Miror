package com.selenite.scanner.link.ui

import androidx.compose.runtime.Composable

/**
 * iOS has no permission to request up front here.
 *
 * Bluetooth authorization is prompted by Core Bluetooth the first time Link starts, and
 * Local Network access has no API to request or reliably query -- starting Nearby's
 * Bonjour browsing from the foreground triggers the system prompt, and denial has to be
 * inferred from the resulting error or timeout. So this simply reports success and lets
 * the transport surface the real outcome.
 */
@Composable
actual fun rememberMirorLinkPermissionRequester(onResult: (granted: Boolean) -> Unit): () -> Unit =
    { onResult(true) }
