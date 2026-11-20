package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformKeyboardNavigationHost() {
    // No-op on Android — keyboard navigation is handled by the platform.
}
