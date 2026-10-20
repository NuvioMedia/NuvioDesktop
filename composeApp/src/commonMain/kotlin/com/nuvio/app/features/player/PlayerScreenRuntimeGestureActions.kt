package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.internalSkipAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal data class PlayerSurfaceGestureCallbacks(
    val onSurfaceTap: State<(Offset) -> Unit>,
    val onSurfaceDoubleTap: State<(Offset) -> Unit>,
    val activateHoldToSpeed: State<() -> Unit>,
    val deactivateHoldToSpeed: State<() -> Unit>,
    val showHorizontalSeekPreview: State<(Long, Long) -> Unit>,
    val showBrightnessFeedback: State<(Float) -> Unit>,
    val showVolumeFeedback: State<(PlayerAudioLevel) -> Unit>,
    val clearLiveGestureFeedback: State<() -> Unit>,
    val revealLockedOverlay: State<() -> Unit>,
    val isHoldToSpeedGestureActive: State<Boolean>,
    val touchGesturesEnabled: State<Boolean>,
    val playerControlsLocked: State<Boolean>,
    val currentPositionMs: State<Long>,
    val currentDurationMs: State<Long>,
    val commitHorizontalSeek: State<(Long) -> Unit>,
)

internal fun PlayerScreenRuntime.showGestureFeedback(feedback: GestureFeedbackState) {
    gestureMessageJob?.cancel()
    gestureFeedback = feedback
    gestureMessageJob = scope.launch {
        delay(900)
        gestureFeedback = null
    }
}

internal fun PlayerScreenRuntime.showGestureMessage(message: String) {
    showGestureFeedback(GestureFeedbackState(message = message))
}

internal fun PlayerScreenRuntime.clearLiveGestureFeedback() {
    liveGestureFeedback = null
}

internal fun PlayerScreenRuntime.revealLockedOverlay() {
    controlsVisible = false
    lockedOverlayVisible = true
}

internal fun PlayerScreenRuntime.lockPlayerControls() {
    playerControlsLocked = true
    controlsVisible = false
    lockedOverlayVisible = false
    pausedOverlayVisible = false
    isScrubbingTimeline = false
    scrubbingPositionMs = null
    gestureMessageJob?.cancel()
    gestureFeedback = null
    liveGestureFeedback = null
    renderedGestureFeedback = null
    showAudioModal = false
    showSubtitleModal = false
    showVideoSettingsModal = false
    showStreamInfo = false
    showSourcesPanel = false
    showEpisodesPanel = false
    episodeStreamsPanelState = EpisodeStreamsPanelState()
    PlayerStreamsRepository.clearEpisodeStreams()
}

internal fun PlayerScreenRuntime.unlockPlayerControls() {
    playerControlsLocked = false
    lockedOverlayVisible = false
    controlsVisible = true
}

internal fun PlayerScreenRuntime.showSeekFeedback(direction: PlayerSeekDirection, amountMs: Long) {
    val seconds = amountMs / 1000L
    if (seconds <= 0L) return
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = if (direction == PlayerSeekDirection.Forward) {
                Res.string.compose_player_seek_feedback_forward
            } else {
                Res.string.compose_player_seek_feedback_backward
            },
            messageArgs = listOf(seconds),
            icon = if (direction == PlayerSeekDirection.Forward) {
                GestureFeedbackIcon.SeekForward
            } else {
                GestureFeedbackIcon.SeekBackward
            },
        ),
    )
}

internal fun PlayerScreenRuntime.showHorizontalSeekPreview(previewPositionMs: Long, baselinePositionMs: Long) {
    val deltaMs = previewPositionMs - baselinePositionMs
    val direction = if (deltaMs < 0L) PlayerSeekDirection.Backward else PlayerSeekDirection.Forward
    liveGestureFeedback = GestureFeedbackState(
        message = formatPlaybackTime(previewPositionMs),
        icon = if (direction == PlayerSeekDirection.Forward) {
            GestureFeedbackIcon.SeekForward
        } else {
            GestureFeedbackIcon.SeekBackward
        },
        secondaryMessageRes = if (deltaMs >= 0L) {
            Res.string.compose_player_seek_delta_forward
        } else {
            Res.string.compose_player_seek_delta_backward
        },
        secondaryMessageArgs = listOf((abs(deltaMs) / 1000f).roundToInt()),
        secondaryMessageColor = if (direction == PlayerSeekDirection.Forward) {
            Color(0xFF6EE7A8)
        } else {
            Color(0xFFFF9A76)
        },
    )
}

internal fun PlayerScreenRuntime.showBrightnessFeedback(level: Float) {
    val percentage = (level.coerceIn(0f, 1f) * 100f).roundToInt()
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = Res.string.compose_player_brightness_level,
            messageArgs = listOf("$percentage%"),
            icon = GestureFeedbackIcon.Brightness,
            level = level.coerceIn(0f, 1f),
        ),
    )
}

internal fun PlayerScreenRuntime.showVolumeFeedback(level: PlayerAudioLevel) {
    val percentage = (level.fraction.coerceIn(0f, 1f) * 100f).roundToInt()
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = if (level.isMuted) {
                Res.string.compose_player_muted
            } else {
                Res.string.compose_player_volume_level
            },
            messageArgs = if (level.isMuted) emptyList() else listOf("$percentage%"),
            icon = if (level.isMuted) GestureFeedbackIcon.VolumeMuted else GestureFeedbackIcon.Volume,
            isDanger = level.isMuted,
            level = if (level.isMuted) 0f else level.fraction.coerceIn(0f, 1f),
        ),
    )
}

internal fun PlayerScreenRuntime.togglePlayback(revealControls: Boolean = true) {
    if (isPartyControlLocked) {
        toggleOwnPlayback(revealControls)
        return
    }
    if (playbackSnapshot.isPlaying) {
        shouldPlay = false
        playerController?.pause()
    } else {
        if (playbackSnapshot.isEnded) {
            playerController?.seekTo(0L)
        }
        shouldPlay = true
        playerController?.play()
    }
    if (revealControls) {
        controlsVisible = true
    }
    publishPartyPlayback(positionMs = if (playbackSnapshot.isEnded) 0L else currentPlaybackPositionMs())
}

internal fun PlayerScreenRuntime.prepareTogglePlaybackForNativeFallback(revealControls: Boolean = true) {
    shouldPlay = !playbackSnapshot.isPlaying
    if (revealControls) {
        controlsVisible = true
    }
}

internal fun PlayerScreenRuntime.toggleFromNativeControls(revealControls: Boolean = true): Boolean {
    if (isPartySynced) {
        togglePlayback(revealControls)
        return true
    }
    prepareTogglePlaybackForNativeFallback(revealControls)
    return false
}

internal fun PlayerScreenRuntime.applyNativePlaybackState(play: Boolean) {
    if (!isPartyControlLocked) {
        val restart = play && playbackSnapshot.isEnded
        shouldPlay = play
        publishPartyPlayback(positionMs = if (restart) 0L else currentPlaybackPositionMs())
        return
    }
    when {
        play == shouldPlay -> Unit
        !play -> {
            partyBehind = true
            shouldPlay = false
        }
        partyBehind -> shouldPlay = true
        else -> {
            playerController?.pause()
            partyBlocksControl()
        }
    }
}

internal fun PlayerScreenRuntime.seekFromNativeControls(offsetMs: Long, revealControls: Boolean = true): Boolean {
    if (isPartySynced) {
        seekBy(offsetMs, revealControls)
        return true
    }
    prepareSeekByForNativeFallback(offsetMs, revealControls)
    return false
}

internal fun PlayerScreenRuntime.finishTimelineSeek(positionMs: Long) {
    val targetMs = if (isPartyControlLocked) ownSeekTarget(positionMs) else positionMs
    if (targetMs == null) {
        isScrubbingTimeline = false
        scrubbingPositionMs = null
        return
    }
    finishTimelineScrub(targetMs)
    playerController?.seekTo(targetMs)
    scheduleProgressSyncAfterSeek()
    publishPartyPlayback(positionMs = targetMs)
}

internal fun PlayerScreenRuntime.skipInterval(interval: SkipInterval) {
    val durationMs = playbackSnapshot.durationMs
    val rawMs = interval.internalSkipAction(skipIntervals, durationMs)?.targetMs ?: return
    val requestedMs = if (durationMs > 0L) rawMs.coerceAtMost(durationMs - 1) else rawMs
    val seekMs = if (isPartyControlLocked) ownSeekTarget(requestedMs) ?: return else requestedMs
    playerController?.seekTo(seekMs)
    scheduleProgressSyncAfterSeek()
    publishPartyPlayback(positionMs = seekMs)
    skipIntervalDismissed = true
}

internal fun PlayerScreenRuntime.setPlaybackPlaying(playing: Boolean) {
    if (playing == shouldPlay) return
    if (isPartyControlLocked) {
        toggleOwnPlayback()
        return
    }
    val restart = playing && playbackSnapshot.isEnded
    if (restart) playerController?.seekTo(0L)
    shouldPlay = playing
    if (playing) playerController?.play() else playerController?.pause()
    val positionMs = pendingSeekTarget
        ?.takeIf { (_, mark) -> mark.elapsedNow() < PendingSeekWindow }
        ?.first
        ?: currentPlaybackPositionMs()
    publishPartyPlayback(positionMs = if (restart) 0L else positionMs)
}

internal fun PlayerScreenRuntime.seekBy(offsetMs: Long, revealControls: Boolean = true) {
    val fromMs = pendingSeekTarget
        ?.takeIf { (_, mark) -> mark.elapsedNow() < PendingSeekWindow }
        ?.first
        ?: currentPlaybackPositionMs()
    val requestedMs = (fromMs + offsetMs).coerceAtLeast(0L)
        .let { if (playbackSnapshot.durationMs > 0L) it.coerceAtMost(playbackSnapshot.durationMs) else it }
    val targetMs = if (isPartyControlLocked) ownSeekTarget(requestedMs) ?: return else requestedMs
    lastManualSkipSeekPositions = fromMs to targetMs
    pendingSeekTarget = targetMs to TimeSource.Monotonic.markNow()
    if (isPartySynced) playerController?.seekTo(targetMs) else playerController?.seekBy(offsetMs)
    applySeekByControlFeedback(offsetMs, revealControls, partyPositionMs = targetMs)
}

internal fun PlayerScreenRuntime.prepareSeekByForNativeFallback(
    offsetMs: Long,
    revealControls: Boolean = true,
) {
    applySeekByControlFeedback(offsetMs, revealControls)
}

private fun PlayerScreenRuntime.applySeekByControlFeedback(
    offsetMs: Long,
    revealControls: Boolean = true,
    partyPositionMs: Long? = null,
) {
    scheduleProgressSyncAfterSeek()
    partyPositionMs?.let { publishPartyPlayback(positionMs = it) }
    if (revealControls) {
        controlsVisible = true
    }
    when {
        offsetMs > 0L -> showSeekFeedback(PlayerSeekDirection.Forward, offsetMs)
        offsetMs < 0L -> showSeekFeedback(PlayerSeekDirection.Backward, abs(offsetMs))
    }
}

internal fun PlayerScreenRuntime.handleDoubleTapSeek(direction: PlayerSeekDirection) {
    handleDoubleTapSeek(direction, sendToController = true)
}

internal fun PlayerScreenRuntime.prepareDoubleTapSeekForNativeFallback(direction: PlayerSeekDirection) {
    handleDoubleTapSeek(direction, sendToController = false)
}

private fun PlayerScreenRuntime.handleDoubleTapSeek(
    direction: PlayerSeekDirection,
    sendToController: Boolean,
) {
    val currentPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    val currentSeekState = accumulatedSeekState
    val nextState = if (currentSeekState?.direction == direction) {
        currentSeekState.copy(amountMs = currentSeekState.amountMs + PlayerDoubleTapSeekStepMs)
    } else {
        PlayerAccumulatedSeekState(
            direction = direction,
            baselinePositionMs = currentPositionMs,
            amountMs = PlayerDoubleTapSeekStepMs,
        )
    }
    val maxDurationMs = playbackSnapshot.durationMs.takeIf { it > 0L }
    val requestedPositionMs = when (direction) {
        PlayerSeekDirection.Backward -> {
            (nextState.baselinePositionMs - nextState.amountMs).coerceAtLeast(0L)
        }
        PlayerSeekDirection.Forward -> {
            val unclamped = nextState.baselinePositionMs + nextState.amountMs
            maxDurationMs?.let { unclamped.coerceAtMost(it) } ?: unclamped
        }
    }
    val targetPositionMs = if (isPartyControlLocked) ownSeekTarget(requestedPositionMs) ?: return else requestedPositionMs
    accumulatedSeekState = nextState
    lastManualSkipSeekPositions = currentPositionMs to targetPositionMs
    if (sendToController) {
        playerController?.seekTo(targetPositionMs)
    }
    scheduleProgressSyncAfterSeek()
    publishPartyPlayback(positionMs = targetPositionMs)
    showSeekFeedback(direction, nextState.amountMs)

    accumulatedSeekResetJob?.cancel()
    accumulatedSeekResetJob = scope.launch {
        delay(PlayerDoubleTapSeekResetDelayMs)
        accumulatedSeekState = null
    }
}

internal fun PlayerScreenRuntime.cycleResizeMode() {
    val nextMode = resizeMode.next()
    resizeMode = nextMode
    lastSyncedSettingsResizeMode = nextMode
    PlayerSettingsRepository.setResizeMode(nextMode)
    showGestureMessage(
        when (nextMode) {
            PlayerResizeMode.Fit -> resizeModeFitLabel
            PlayerResizeMode.Fill -> resizeModeFillLabel
            PlayerResizeMode.Zoom -> resizeModeZoomLabel
            PlayerResizeMode.Stretch -> resizeModeStretchLabel
        },
    )
    controlsVisible = true
}

internal fun PlayerScreenRuntime.cyclePlaybackSpeed() {
    if (partyBlocksControl()) return
    val speeds = listOf(1f, 1.25f, 1.5f, 2f)
    val current = playbackSnapshot.playbackSpeed
    val next = speeds.firstOrNull { it > current + 0.01f } ?: speeds.first()
    applyPlaybackSpeed(next)
    showGestureMessage(formatPlaybackSpeedLabel(next))
    controlsVisible = true
}

internal fun PlayerScreenRuntime.selectPlaybackSpeed(speed: Float) {
    if (partyBlocksControl()) return
    applyPlaybackSpeed(speed)
}

private fun PlayerScreenRuntime.applyPlaybackSpeed(speed: Float) {
    partyRateNudge = null
    playerController?.setPlaybackSpeed(speed)
    publishPartyPlayback(rate = speed)
}

internal fun PlayerScreenRuntime.activateHoldToSpeed() {
    if (!playerSettingsUiState.holdToSpeedEnabled) return
    if (isPartySynced) return
    val controller = playerController ?: return
    if (speedBoostRestoreSpeed != null) return

    val targetSpeed = playerSettingsUiState.holdToSpeedValue
    val currentSpeed = playbackSnapshot.playbackSpeed
    if (abs(currentSpeed - targetSpeed) < 0.01f) return

    isHoldToSpeedGestureActive = true
    speedBoostRestoreSpeed = currentSpeed
    controller.setPlaybackSpeed(targetSpeed)
    liveGestureFeedback = GestureFeedbackState(
        message = formatPlaybackSpeedLabel(targetSpeed),
        icon = GestureFeedbackIcon.Speed,
    )
    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

internal fun PlayerScreenRuntime.deactivateHoldToSpeed() {
    isHoldToSpeedGestureActive = false
    val restoreSpeed = speedBoostRestoreSpeed ?: return
    playerController?.setPlaybackSpeed(restoreSpeed)
    speedBoostRestoreSpeed = null
    liveGestureFeedback = null
}

@Composable
internal fun PlayerScreenRuntime.rememberSurfaceGestureCallbacks(): PlayerSurfaceGestureCallbacks {
    val onSurfaceTap = rememberUpdatedState { offset: Offset ->
        if (playerControlsLocked) {
            revealLockedOverlay()
            return@rememberUpdatedState
        }
        val centerStart = layoutSize.width * PlayerLeftGestureBoundary
        val centerEnd = layoutSize.width * PlayerRightGestureBoundary
        if (controlsVisible && offset.x in centerStart..centerEnd) {
            controlsVisible = false
        } else {
            controlsVisible = !controlsVisible
        }
    }
    val onSurfaceDoubleTap = rememberUpdatedState { offset: Offset ->
        if (playerControlsLocked) {
            revealLockedOverlay()
            return@rememberUpdatedState
        }
        if (!playerSettingsUiState.touchGesturesEnabled) {
            controlsVisible = !controlsVisible
            return@rememberUpdatedState
        }
        when {
            offset.x < layoutSize.width * PlayerLeftGestureBoundary -> {
                handleDoubleTapSeek(PlayerSeekDirection.Backward)
            }
            offset.x > layoutSize.width * PlayerRightGestureBoundary -> {
                handleDoubleTapSeek(PlayerSeekDirection.Forward)
            }
            else -> controlsVisible = !controlsVisible
        }
    }
    return PlayerSurfaceGestureCallbacks(
        onSurfaceTap = onSurfaceTap,
        onSurfaceDoubleTap = onSurfaceDoubleTap,
        activateHoldToSpeed = rememberUpdatedState(::activateHoldToSpeed),
        deactivateHoldToSpeed = rememberUpdatedState(::deactivateHoldToSpeed),
        showHorizontalSeekPreview = rememberUpdatedState(::showHorizontalSeekPreview),
        showBrightnessFeedback = rememberUpdatedState(::showBrightnessFeedback),
        showVolumeFeedback = rememberUpdatedState(::showVolumeFeedback),
        clearLiveGestureFeedback = rememberUpdatedState(::clearLiveGestureFeedback),
        revealLockedOverlay = rememberUpdatedState(::revealLockedOverlay),
        isHoldToSpeedGestureActive = rememberUpdatedState(isHoldToSpeedGestureActive),
        touchGesturesEnabled = rememberUpdatedState(playerSettingsUiState.touchGesturesEnabled),
        playerControlsLocked = rememberUpdatedState(playerControlsLocked),
        currentPositionMs = rememberUpdatedState(playbackSnapshot.positionMs.coerceAtLeast(0L)),
        currentDurationMs = rememberUpdatedState(playbackSnapshot.durationMs),
        commitHorizontalSeek = rememberUpdatedState { requestedPositionMs: Long ->
            val targetPositionMs = if (isPartyControlLocked) {
                ownSeekTarget(requestedPositionMs) ?: return@rememberUpdatedState
            } else {
                requestedPositionMs
            }
            lastManualSkipSeekPositions = playbackSnapshot.positionMs to targetPositionMs
            playerController?.seekTo(targetPositionMs)
            scheduleProgressSyncAfterSeek()
            publishPartyPlayback(positionMs = targetPositionMs)
        },
    )
}

private val PendingSeekWindow = 1_500.milliseconds
