package com.selenite.scanner.link.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.selenite.scanner.link.MirorLinkPermissions

@Composable
actual fun rememberMirorLinkPermissionRequester(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        onResult(grants.values.all { it })
    }
    return {
        val missing = com.selenite.scanner.util.AndroidContext
            ?.let { MirorLinkPermissions.missing(it) }
            .orEmpty()
        if (missing.isEmpty()) {
            onResult(true)
        } else {
            // The permission dialog pauses but does not stop the Activity, which is why
            // the Link lifecycle observer counts started activities rather than reacting
            // to onPause -- otherwise asking for permission would cancel the session.
            launcher.launch(missing.toTypedArray())
        }
    }
}
