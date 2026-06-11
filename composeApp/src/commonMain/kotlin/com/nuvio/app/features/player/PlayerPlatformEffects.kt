package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntSize

interface PlayerGestureController {
    fun currentBrightness(): Float?
    fun setBrightness(level: Float): Float?
    fun currentVolume(): PlayerAudioLevel?
    fun setVolume(level: Float): PlayerAudioLevel?
}

interface PlayerFullscreenController {
    val isFullscreenSupported: Boolean
    val isFullscreen: Boolean
    fun toggleFullscreen()
}

data class PlayerKeyboardShortcutHandlers(
    val toggleFullscreen: () -> Unit,
    val togglePlayback: () -> Unit,
    val seekForward: () -> Unit,
    val seekBackward: () -> Unit,
    val volumeUp: () -> Unit,
    val volumeDown: () -> Unit,
    val toggleMute: () -> Unit,
    val cyclePlaybackSpeed: () -> Unit,
    val playNextEpisode: () -> Unit,
    val skipActiveSegment: () -> Unit,
)

data class PlayerAudioLevel(
    val fraction: Float,
    val isMuted: Boolean,
)

@Composable
expect fun LockPlayerToLandscape()

@Composable
expect fun EnterImmersivePlayerMode(keepScreenAwake: Boolean)

@Composable
expect fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    playerSize: IntSize,
)

@Composable
expect fun ManagePlayerCursorVisibility(visible: Boolean)

@Composable
expect fun rememberPlayerGestureController(): PlayerGestureController?

@Composable
expect fun rememberPlayerFullscreenController(): PlayerFullscreenController

@Composable
expect fun ManageFullscreenKeyboardShortcuts(
    isHomeRouteActive: Boolean,
    onBack: () -> Unit = {},
)

@Composable
expect fun BindPlayerKeyboardShortcuts(
    enabled: Boolean,
    handlers: PlayerKeyboardShortcutHandlers,
)

expect val usesNativePlayerChrome: Boolean

expect val usesAnimatedPlayerChrome: Boolean
