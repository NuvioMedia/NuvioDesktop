package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val currentOnBack = rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}

        val callback: () -> Unit = { currentOnBack.value() }
        DesktopBackHandlers.pushBack(callback)
        onDispose {
            DesktopBackHandlers.removeBack(callback)
        }
    }
}
