package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun PlatformEscapeShortcut(handler: () -> Unit) {
    // No-op on Android — Back is handled by the platform.
}
