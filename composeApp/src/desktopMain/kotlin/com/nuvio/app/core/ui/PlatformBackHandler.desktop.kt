package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

// Registers with Compose's window back dispatcher, which already receives Esc and orders
// handlers against dialogs and NavDisplay the same way BackHandler does on Android.
@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )

    // Escape is observed by an AWT dispatcher before Compose sees it, so register the same modal
    // handler there. The most recently enabled handler wins (dialogs, sheets, settings sub-pages).
    val latestOnBack = remember(onBack) { { onBack() } }
    DisposableEffect(enabled, latestOnBack) {
        if (!enabled) {
            return@DisposableEffect onDispose {}
        }
        DesktopEscapeShortcut.push(latestOnBack)
        onDispose { DesktopEscapeShortcut.pop(latestOnBack) }
    }
}
