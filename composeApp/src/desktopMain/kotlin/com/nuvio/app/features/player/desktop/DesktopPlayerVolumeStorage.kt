package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.DesktopStorage

internal object DesktopPlayerVolumeStorage {
    private const val VolumeLevelKey = "volume_level"
    private val store = DesktopStorage.store("nuvio_player_runtime")

    fun loadVolumeLevel(): Float? =
        store.getFloat(VolumeLevelKey)?.coerceIn(0f, 1f)

    fun saveVolumeLevel(level: Float) {
        store.putFloat(VolumeLevelKey, level.coerceIn(0f, 1f))
    }
}
