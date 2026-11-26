package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual object DesktopTraySettings {
    actual val isSupported: Boolean = false
    actual val closeToTray: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    actual fun setCloseToTray(enabled: Boolean) = Unit
}
