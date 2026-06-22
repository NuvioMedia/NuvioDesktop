package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nuvio.app.features.player.desktop.desktopFullscreenChanges
import com.nuvio.app.features.player.desktop.isDesktopAppFullscreen
import com.nuvio.app.features.player.desktop.toggleDesktopAppFullscreen

internal actual val isFullscreenActionSupported: Boolean = true

@Composable
internal actual fun isFullscreenActionActive(): Boolean {
    val version by desktopFullscreenChanges.collectAsState()
    version
    return isDesktopAppFullscreen()
}

internal actual fun toggleFullscreenAction() {
    toggleDesktopAppFullscreen()
}
