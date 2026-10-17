package com.nuvio.app.features.details.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.nuvio.app.features.trailer.TrailerExtractionPlatform
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TrailerFillFrameScale = 1.35f

@Composable
actual fun HeroTrailerPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    startPositionMillis: Long,
    fillFrame: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
) {
    key(sourceUrl, sourceAudioUrl, startPositionMillis) {
        DesktopTrailerPlayerSession(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            playWhenReady = playWhenReady,
            muted = muted,
            startPositionMillis = startPositionMillis,
            fillFrame = fillFrame,
            modifier = modifier,
            onReady = onReady,
            onEnded = onEnded,
            onError = onError,
        )
    }
}

@Composable
private fun DesktopTrailerPlayerSession(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    startPositionMillis: Long,
    fillFrame: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
) {
    val player = rememberVideoPlayerState()
    val callbackScope = rememberCoroutineScope()
    val latestOnReady = rememberUpdatedState(onReady)
    val latestOnEnded = rememberUpdatedState(onEnded)
    val latestOnError = rememberUpdatedState(onError)
    var mediaReady by remember { mutableStateOf(false) }
    var terminalReported by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        player.onPlaybackEnded = {
            callbackScope.launch {
                if (!terminalReported) {
                    terminalReported = true
                    mediaReady = false
                    TrailerExtractionPlatform.diagnostic("compose player ended")
                    latestOnEnded.value()
                }
            }
        }
        onDispose {
            player.onPlaybackEnded = null
        }
    }

    LaunchedEffect(player, sourceUrl, sourceAudioUrl, startPositionMillis) {
        mediaReady = false
        terminalReported = false
        player.clearError()
        player.loop = false
        player.volume = if (muted) 0f else 1f
        TrailerExtractionPlatform.diagnostic(
            "compose player open ${TrailerExtractionPlatform.describeUrl(sourceUrl)} " +
                "separateAudio=${!sourceAudioUrl.isNullOrBlank()} startMs=$startPositionMillis",
        )
        player.openUri(
            uri = sourceUrl,
            audioUri = sourceAudioUrl,
            initializeplayerState = InitialPlayerState.PAUSE,
        )

        val opened = withTimeoutOrNull(12_000L) {
            snapshotFlow { player.hasMedia to player.error }
                .first { (hasMedia, error) -> hasMedia || error != null }
        }
        val error = opened?.second ?: player.error
        if (opened == null || error != null) {
            if (!terminalReported) {
                terminalReported = true
                TrailerExtractionPlatform.diagnostic(
                    "blocked stage=compose_player_open detail=${error ?: "timeout"}",
                )
                latestOnError.value()
            }
            return@LaunchedEffect
        }

        if (startPositionMillis > 0L && player.duration > 0.0) {
            val normalizedPosition = (
                startPositionMillis.toDouble() /
                    (player.duration * 1_000.0) *
                    1_000.0
                ).toFloat()
            player.seekTo(normalizedPosition)
        }
        mediaReady = true
        if (playWhenReady) {
            player.play()
        }
        TrailerExtractionPlatform.diagnostic("compose player ready playing=$playWhenReady")
        latestOnReady.value()
    }

    LaunchedEffect(player, playWhenReady, mediaReady) {
        if (mediaReady) {
            if (playWhenReady) {
                player.play()
            } else {
                player.pause()
            }
        }
    }

    LaunchedEffect(player, muted) {
        player.volume = if (muted) 0f else 1f
    }

    LaunchedEffect(player) {
        snapshotFlow { player.error }
            .first { it != null }
            ?.let { error ->
                if (!terminalReported) {
                    terminalReported = true
                    mediaReady = false
                    TrailerExtractionPlatform.diagnostic(
                        "blocked stage=compose_player detail=$error",
                    )
                    latestOnError.value()
                }
            }
    }

    val surfaceAlpha by animateFloatAsState(
        targetValue = if (playWhenReady) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "hero_surface_alpha",
    )

    Box(modifier = modifier.clipToBounds()) {
        VideoPlayerSurface(
            playerState = player,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = surfaceAlpha
                    if (fillFrame) {
                        scaleX = TrailerFillFrameScale
                        scaleY = TrailerFillFrameScale
                    }
                },
            contentScale = if (fillFrame) ContentScale.Crop else ContentScale.Fit,
        )
    }
}
