package com.nuvio.app.features.player.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.SubtitleTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class UnavailableDesktopPlayerBackend(
    override val backendName: String,
    error: DesktopPlayerError,
) : DesktopPlayerBackend {
    override val id: String = "unavailable-${System.identityHashCode(this)}"

    private val stateFlow = MutableStateFlow(
        DesktopPlayerState(
            phase = DesktopPlayerPhase.Error,
            backendName = backendName,
            error = error,
            diagnostics = error.technicalMessage,
        ),
    )
    override val state: StateFlow<DesktopPlayerState> = stateFlow

    override val controller: PlayerEngineController = object : PlayerEngineController {
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun getAudioTracks(): List<AudioTrack> = emptyList()
        override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }

    override suspend fun load(request: DesktopPlayerRequest) = Unit
    override fun setResizeMode(resizeMode: PlayerResizeMode) = Unit
    override fun releaseSoft() = Unit
    override fun close() = Unit

    @Composable
    override fun Surface(modifier: Modifier) {
        Box(modifier = modifier.background(Color.Black))
    }
}
