package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val registration = remember { PlatformBackRegistration(enabled, onBack) }
    SideEffect {
        registration.enabled = enabled
        registration.onBack = onBack
    }
    DisposableEffect(registration) {
        PlatformBackDispatcher.register(registration)
        onDispose { PlatformBackDispatcher.unregister(registration) }
    }
}
