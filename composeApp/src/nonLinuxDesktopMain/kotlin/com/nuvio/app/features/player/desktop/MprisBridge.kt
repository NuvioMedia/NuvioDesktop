package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerNowPlayingInfo
import com.nuvio.app.features.player.PlayerPlaybackSnapshot

internal object MprisBridge {
    fun start() = Unit
    fun stop() = Unit
    fun register(controller: NativePlayerController) = Unit
    fun unregister(controller: NativePlayerController) = Unit
    fun updateMetadata(info: PlayerNowPlayingInfo) = Unit
    fun updatePlayback(snapshot: PlayerPlaybackSnapshot) = Unit
    fun notifySeek(positionMs: Long) = Unit
    fun updateVolume(level: Float) = Unit
    fun updateNavigation(canGoNext: Boolean, canGoPrevious: Boolean) = Unit
    fun clear() = Unit
}
