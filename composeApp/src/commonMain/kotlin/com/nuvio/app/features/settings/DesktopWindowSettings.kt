package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.StateFlow

internal expect object DesktopWindowSettings {
    val isSupported: Boolean
    val escapeExitsFullscreen: StateFlow<Boolean>

    fun setEscapeExitsFullscreen(enabled: Boolean)
}
