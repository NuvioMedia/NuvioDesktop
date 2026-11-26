package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.StateFlow

internal expect object DesktopTraySettings {
    val isSupported: Boolean
    val closeToTray: StateFlow<Boolean>

    fun setCloseToTray(enabled: Boolean)
}
