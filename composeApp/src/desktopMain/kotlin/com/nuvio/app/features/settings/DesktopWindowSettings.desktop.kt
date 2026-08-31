package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual object DesktopWindowSettings {
    private const val EscapeExitsFullscreenKey = "escape_exits_fullscreen"
    private val store = DesktopStorage.store("nuvio_desktop_window_settings")
    private val _escapeExitsFullscreen = MutableStateFlow(
        store.getBoolean(EscapeExitsFullscreenKey) ?: true,
    )

    actual val isSupported: Boolean = true
    actual val escapeExitsFullscreen: StateFlow<Boolean> =
        _escapeExitsFullscreen.asStateFlow()

    actual fun setEscapeExitsFullscreen(enabled: Boolean) {
        if (_escapeExitsFullscreen.value == enabled) return
        _escapeExitsFullscreen.value = enabled
        store.putBoolean(EscapeExitsFullscreenKey, enabled)
    }
}
