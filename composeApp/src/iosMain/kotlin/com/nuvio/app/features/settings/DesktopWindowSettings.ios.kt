package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal actual object DesktopWindowSettings {
    actual val isSupported: Boolean = false
    actual val escapeExitsFullscreen: StateFlow<Boolean> = MutableStateFlow(true)

    actual fun setEscapeExitsFullscreen(enabled: Boolean) = Unit
}
