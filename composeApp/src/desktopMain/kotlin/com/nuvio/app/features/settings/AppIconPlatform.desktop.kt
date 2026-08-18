package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage

internal actual object AppIconPlatform {
    actual val isSupported: Boolean = true
    actual val requiresCloseConfirmation: Boolean = false

    private val store = DesktopStorage.store("nuvio_app_icon")
    private const val selectedIconKey = "selected_icon"
    private const val blackBackgroundKey = "black_background"

    actual fun currentIconName(): String? = store.getString(selectedIconKey)

    actual fun currentBlackBackground(): Boolean = store.getString(blackBackgroundKey)?.toBooleanStrictOrNull() ?: false

    actual fun setBlackBackground(enabled: Boolean): Boolean {
        store.putBoolean(blackBackgroundKey, enabled)
        return true
    }

    actual suspend fun activateIcon(name: String?): Boolean {
        store.putString(selectedIconKey, name)
        return true
    }
}