package com.nuvio.app.features.details.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.features.trailer.desktop.NativeMpvSurfacePlayer
import kotlin.math.roundToInt

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
        WindowsMpvTrailerPlayerSession(
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
private fun WindowsMpvTrailerPlayerSession(
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
    val coroutineScope = rememberCoroutineScope()
    val latestOnReady = rememberUpdatedState(onReady)
    val latestOnEnded = rememberUpdatedState(onEnded)
    val latestOnError = rememberUpdatedState(onError)

    val player = remember(sourceUrl, sourceAudioUrl, startPositionMillis, fillFrame) {
        NativeMpvSurfacePlayer(
            videoUrl = sourceUrl,
            audioUrl = sourceAudioUrl,
            startPositionMillis = startPositionMillis,
            playWhenReady = playWhenReady,
            initialMuted = muted,
            fillFrame = fillFrame,
            scope = coroutineScope,
            onReady = { latestOnReady.value() },
            onEnded = { latestOnEnded.value() },
            onError = { latestOnError.value() },
        )
    }

    DisposableEffect(player) {
        onDispose {
            player.dispose()
        }
    }

    LaunchedEffect(player, playWhenReady) {
        player.setPaused(!playWhenReady)
    }

    LaunchedEffect(player, muted) {
        player.setMuted(muted)
    }

    val surfaceAlpha by animateFloatAsState(
        targetValue = if (playWhenReady) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "hero_surface_alpha",
    )

    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx().roundToInt() }
        val heightPx = with(density) { maxHeight.toPx().roundToInt() }

        LaunchedEffect(player, widthPx, heightPx) {
            if (widthPx > 0 && heightPx > 0) {
                player.setSize(widthPx, heightPx)
            }
        }

        val currentFrame by player.currentFrame

        Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            if (surfaceAlpha > 0.001f) {
                currentFrame?.let { frame ->
                    drawImage(
                        image = frame,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        alpha = surfaceAlpha,
                    )
                }
            }
        }
    }
}
