package com.selenite.scanner.link.ui

import androidx.compose.runtime.Composable

/**
 * In-context permission request for Miror Link.
 *
 * Returns a callback that asks for whatever this OS version needs. Nothing is requested
 * at launch -- only when the user actually opens Link, which is also the only time the
 * radios run.
 */
@Composable
expect fun rememberMirorLinkPermissionRequester(onResult: (granted: Boolean) -> Unit): () -> Unit
