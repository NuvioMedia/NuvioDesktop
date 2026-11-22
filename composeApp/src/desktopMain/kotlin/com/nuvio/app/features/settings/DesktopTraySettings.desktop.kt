package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.desktop.DesktopHostOs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.SystemTray

private const val CloseToTrayKey = "close_to_tray"

internal actual object DesktopTraySettings {
    private val store by lazy { DesktopStorage.store("nuvio_desktop_tray_settings") }
    private val closeToTrayState by lazy {
        MutableStateFlow(store.getBoolean(CloseToTrayKey) == true && isSupported)
    }
    private val closeToTrayFlow by lazy { closeToTrayState.asStateFlow() }

    actual val isSupported: Boolean
        get() = DesktopHostOs.current == DesktopHostOs.WINDOWS && SystemTray.isSupported()

    actual val closeToTray: StateFlow<Boolean>
        get() = closeToTrayFlow

    actual fun setCloseToTray(enabled: Boolean) {
        require(!enabled || isSupported) { "The system tray is unavailable on this device." }
        store.putBoolean(CloseToTrayKey, enabled)
        closeToTrayState.value = enabled
    }
}
