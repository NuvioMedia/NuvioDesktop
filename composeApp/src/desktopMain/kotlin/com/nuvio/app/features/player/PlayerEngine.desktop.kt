package com.nuvio.app.features.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.DesktopPlayerLaunchShield
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.desktop.WaylandPlayerHost
import com.nuvio.app.features.player.desktop.toggleDesktopAppFullscreen
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import kotlinx.coroutines.delay

@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    streamType: String?,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    initialPositionMs: Long,
    useNativeController: Boolean,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    if (DesktopHostOs.current == DesktopHostOs.LINUX && DesktopHostOs.isWayland) {
        // Wayland: no wid support, must use SW rendering with Compose Canvas overlay
        LinuxWaylandPlayerSurface(
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            modifier = modifier,
            playWhenReady = playWhenReady,
            resizeMode = resizeMode,
            initialPositionMs = initialPositionMs,
            playerControlsState = playerControlsState,
            onPlayerControlsAction = onPlayerControlsAction,
            onPlayerControlsEvent = onPlayerControlsEvent,
            onPlayerControlsScrubChange = onPlayerControlsScrubChange,
            onPlayerControlsScrubFinished = onPlayerControlsScrubFinished,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
    } else if (DesktopHostOs.current == DesktopHostOs.MACOS || DesktopHostOs.current == DesktopHostOs.WINDOWS || DesktopHostOs.current == DesktopHostOs.LINUX) {
        // macOS, Windows, and Linux X11: GPU-direct rendering via native view pointer
        NativePlayerSurface(
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            modifier = modifier,
            playWhenReady = playWhenReady,
            resizeMode = resizeMode,
            initialPositionMs = initialPositionMs,
            playerControlsState = playerControlsState,
            onPlayerControlsAction = onPlayerControlsAction,
            onPlayerControlsEvent = onPlayerControlsEvent,
            onPlayerControlsScrubChange = onPlayerControlsScrubChange,
            onPlayerControlsScrubFinished = onPlayerControlsScrubFinished,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
    } else {
        DesktopStubPlayerSurface(
            modifier = modifier,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
        )
    }
}

/**
 * Linux Wayland path: renders video frames in a Compose [Canvas] so controls overlay correctly.
 * mpv renders to EGL FBO (via GBM /dev/dri/renderD128), glReadPixels to byte[], Skia Image in Canvas.
 */
@Composable
private fun LinuxWaylandPlayerSurface(
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    initialPositionMs: Long,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val host = remember { WaylandPlayerHost() }
    val controller = remember(host) { NativePlayerController(host) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var frameTick by remember { mutableIntStateOf(0) }
    var disposed by remember { mutableStateOf(false) }

    val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
    val latestOnPlayerControlsEvent = rememberUpdatedState(onPlayerControlsEvent)
    val latestOnPlayerControlsScrubChange = rememberUpdatedState(onPlayerControlsScrubChange)
    val latestOnPlayerControlsScrubFinished = rememberUpdatedState(onPlayerControlsScrubFinished)
    val latestOnError = rememberUpdatedState(onError)

    LaunchedEffect(controller) {
        onControllerReady(controller)
    }

    LaunchedEffect(controller) {
        controller.setControlCallbacks(
            onAction = { action -> onPlayerControlsAction(action) },
            onEvent = { type, value -> latestOnPlayerControlsEvent.value(type, value) },
            onScrubChange = { positionMs -> latestOnPlayerControlsScrubChange.value(positionMs) },
            onScrubFinished = { positionMs -> latestOnPlayerControlsScrubFinished.value(positionMs) },
        )
    }

    LaunchedEffect(controller, sourceUrl, playbackHeaders) {
        DesktopPlayerLaunchShield.hideAfter()
        delay(16L)
        controller.attach(
            sourceUrl = sourceUrl,
            sourceHeaders = playbackHeaders,
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs,
            onError = { message -> latestOnError.value(message) },
        )
    }

    LaunchedEffect(controller, playWhenReady) {
        if (playWhenReady) controller.play() else controller.pause()
    }

    LaunchedEffect(controller, resizeMode) {
        controller.setResizeMode(resizeMode)
    }

    LaunchedEffect(controller, playerControlsState) {
        controller.updateControls(playerControlsState)
    }

    LaunchedEffect(controller) {
        try {
            while (true) {
                onSnapshot(controller.snapshot())
                delay(500L)
            }
        } finally { /* coroutine cancelled on dispose */ }
    }

    // Frame render loop
    LaunchedEffect(controller) {
        try {
            while (true) {
                delay(8)
                if (disposed) break
                val size = surfaceSize
                if (host.nativeHandle != 0L && size.width > 0 && size.height > 0) {
                    if (host.renderFrame(size.width, size.height)) {
                        frameTick++
                    }
                }
            }
        } finally { /* coroutine cancelled on dispose */ }
    }

    DisposableEffect(controller, sourceUrl, playbackHeaders) {
        onDispose { controller.dispose() }
    }

    DisposableEffect(host) {
        onDispose {
            disposed = true
            host.nativeHandle = 0L
            host.dispose()
            frameTick++
        }
    }

    DisposableEffect(Unit) {
        val listener = AWTEventListener { awtEvent ->
            when (awtEvent.id) {
                MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED -> {
                    host.noteCursorActivity()
                    latestOnPlayerControlsEvent.value("keepChromeVisible", 0.0)
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(
            listener,
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK,
        )
        onDispose {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            event.changes.firstOrNull()?.scrollDelta?.y?.let { delta ->
                                controller.seekBy(if (delta < 0f) 10000L else -10000L)
                            }
                        }
                    }
                }
            },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { surfaceSize = it },
        ) {
            frameTick // read to trigger recomposition
            if (!disposed) {
                val skiaImage = host.latestImage
                if (skiaImage != null && !skiaImage.isClosed) {
                    val canvas = drawContext.canvas.nativeCanvas
                    canvas.drawImageRect(
                        skiaImage,
                        org.jetbrains.skia.Rect.makeWH(skiaImage.width.toFloat(), skiaImage.height.toFloat()),
                        org.jetbrains.skia.Rect.makeWH(size.width, size.height),
                        org.jetbrains.skia.SamplingMode.DEFAULT,
                        org.jetbrains.skia.Paint(),
                        false,
                    )
                }
            }
        }
    }
}

/**
 * macOS / Windows / Linux X11 path: uses AWT Canvas + SwingPanel.
 * These platforms get the native view pointer for hardware-accelerated rendering.
 */
@Composable
private fun NativePlayerSurface(
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    initialPositionMs: Long,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val host = remember { NativePlayerHost() }
    val controller = remember(host) { NativePlayerController(host) }
    val hostFirstPaintComplete = remember { mutableStateOf(false) }
    val hostFirstFullSizePaintComplete = remember { mutableStateOf(false) }
    LaunchedEffect(sourceUrl) {
        DesktopPlayerLaunchShield.showForActiveWindow()
    }
    val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
    val latestOnPlayerControlsAction = rememberUpdatedState(onPlayerControlsAction)
    val latestOnPlayerControlsEvent = rememberUpdatedState(onPlayerControlsEvent)
    val latestOnPlayerControlsScrubChange = rememberUpdatedState(onPlayerControlsScrubChange)
    val latestOnPlayerControlsScrubFinished = rememberUpdatedState(onPlayerControlsScrubFinished)
    val latestOnError = rememberUpdatedState(onError)
    val playerSettings by PlayerSettingsRepository.uiState.collectAsState()
    val decoderPriority = playerSettings.decoderPriority
    val nvidiaRtxSuperResolutionEnabled = playerSettings.nvidiaRtxSuperResolutionEnabled

    LaunchedEffect(controller) {
        onControllerReady(controller)
    }

    DisposableEffect(host) {
        host.onDisplayableChanged = { displayable ->
            if (!displayable) {
                hostFirstPaintComplete.value = false
                hostFirstFullSizePaintComplete.value = false
            }
        }
        host.onFirstPaint = {
            hostFirstPaintComplete.value = true
        }
        host.onFirstFullSizePaint = {
            hostFirstFullSizePaintComplete.value = true
            DesktopPlayerLaunchShield.hideAfter()
        }
        onDispose {
            host.onDisplayableChanged = null
            host.onFirstPaint = null
            host.onFirstFullSizePaint = null
            host.dispose()
            DesktopPlayerLaunchShield.hide()
        }
    }

    LaunchedEffect(controller) {
        controller.setControlCallbacks(
            onAction = { action -> latestOnPlayerControlsAction.value(action) },
            onEvent = { type, value -> latestOnPlayerControlsEvent.value(type, value) },
            onScrubChange = { positionMs -> latestOnPlayerControlsScrubChange.value(positionMs) },
            onScrubFinished = { positionMs -> latestOnPlayerControlsScrubFinished.value(positionMs) },
        )
    }

    DisposableEffect(controller, sourceUrl, playbackHeaders) {
        onDispose { controller.dispose() }
    }

    LaunchedEffect(controller, sourceUrl, playbackHeaders, decoderPriority, nvidiaRtxSuperResolutionEnabled, hostFirstFullSizePaintComplete.value) {
        if (!hostFirstFullSizePaintComplete.value) {
            return@LaunchedEffect
        }
        delay(16L)
        controller.attach(
            sourceUrl = sourceUrl,
            sourceHeaders = playbackHeaders,
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs,
            decoderPriority = decoderPriority,
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled,
            onError = { message -> latestOnError.value(message) },
        )
    }

    LaunchedEffect(controller, playWhenReady) {
        if (playWhenReady) controller.play() else controller.pause()
    }

    LaunchedEffect(controller, resizeMode) {
        controller.setResizeMode(resizeMode)
    }

    LaunchedEffect(controller, playerControlsState) {
        controller.updateControls(playerControlsState)
    }

    LaunchedEffect(controller) {
        while (true) {
            onSnapshot(controller.snapshot())
            delay(500L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        SwingPanel(
            factory = { host },
            modifier = if (hostFirstPaintComplete.value) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .align(Alignment.BottomEnd)
                    .requiredSize(1.dp)
            },
            background = Color.Black,
        )
    }
}

@Composable
private fun DesktopStubPlayerSurface(
    modifier: Modifier,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
) {
    val controller = remember { DesktopStubPlayerController() }

    LaunchedEffect(controller) {
        onControllerReady(controller)
        onSnapshot(PlayerPlaybackSnapshot(isLoading = false))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Desktop in-app playback is not available yet.",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private class DesktopStubPlayerController : PlayerEngineController {
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
