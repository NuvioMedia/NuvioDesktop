package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerPlaybackSnapshot

internal enum class DesktopPlayerPhase {
    Idle,
    Preparing,
    Ready,
    Playing,
    Paused,
    Buffering,
    Ended,
    Error,
    Closed,
}

internal data class DesktopPlayerState(
    val phase: DesktopPlayerPhase = DesktopPlayerPhase.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val backendName: String,
    val diagnostics: String? = null,
    val error: DesktopPlayerError? = null,
) {
    fun toSnapshot(): PlayerPlaybackSnapshot =
        PlayerPlaybackSnapshot(
            isLoading = phase == DesktopPlayerPhase.Preparing || phase == DesktopPlayerPhase.Buffering,
            isPlaying = phase == DesktopPlayerPhase.Playing,
            isEnded = phase == DesktopPlayerPhase.Ended,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            bufferedPositionMs = bufferedPositionMs.coerceAtLeast(0L),
            playbackSpeed = playbackSpeed,
        )
}
