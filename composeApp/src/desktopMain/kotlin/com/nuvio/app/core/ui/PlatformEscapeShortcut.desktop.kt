package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

@Composable
internal actual fun PlatformEscapeShortcut(handler: () -> Unit) {
    val current = rememberUpdatedState(handler)
    DisposableEffect(Unit) {
        val bridged: () -> Unit = { current.value() }
        DesktopEscapeShortcut.register(bridged)
        onDispose { DesktopEscapeShortcut.unregister(bridged) }
    }
}
