package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.DesktopStorage

internal object DesktopVideoClickStorage {
    private const val VideoClickTogglesPlaybackKey = "video_click_toggles_playback"
    private val store = DesktopStorage.store("nuvio_player_runtime")

    fun loadVideoClickTogglesPlayback(): Boolean? =
        store.getBoolean(VideoClickTogglesPlaybackKey)

    fun saveVideoClickTogglesPlayback(enabled: Boolean) {
        store.putBoolean(VideoClickTogglesPlaybackKey, enabled)
    }
}
