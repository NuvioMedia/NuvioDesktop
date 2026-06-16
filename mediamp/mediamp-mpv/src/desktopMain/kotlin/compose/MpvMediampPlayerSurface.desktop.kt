@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.openani.mediamp.mpv.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.LocalWindow
import com.sun.jna.Native
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SurfaceOrigin
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.internal.Platform
import org.openani.mediamp.internal.currentPlatform
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.utils.ChildHwndProvider
import org.openani.mediamp.mpv.utils.OpenGLComponentProvider
import org.openani.mediamp.mpv.utils.findSkiaLayer
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(InternalMediampApi::class)
@Composable
actual fun MpvMediampPlayerSurface(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    LegacyGlSurface(player, modifier)
}

@OptIn(InternalMediampApi::class)
@Composable
private fun WindowsWidSurface(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    val window = LocalWindow.current as ComposeWindow
    val provider = remember(window) {
        ChildHwndProvider(Native.getWindowPointer(window))
    }
    var childHwnd by remember { mutableLongStateOf(0L) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var surfaceOffset by remember { mutableStateOf(IntOffset.Zero) }
    var initialized by remember { mutableStateOf(false) }
    val fullscreenRevision = LocalWindowFullscreenRevision.current

    // Create HWND early (before any user interaction) so wid is set
    // before loadfile. Initial position/size is corrected by layout below.
    DisposableEffect(window, player) {
        val hwnd = provider.create(
            x = surfaceOffset.x,
            y = surfaceOffset.y,
            width = surfaceSize.width.coerceAtLeast(320),
            height = surfaceSize.height.coerceAtLeast(240),
        )
        if (hwnd != 0L) {
            childHwnd = hwnd
        }

        onDispose {
            if (initialized) {
                player.impl.command("stop")
            }
            provider.destroy()
            childHwnd = 0L
        }
    }

    // Initialize player in background to prevent UI freeze
    LaunchedEffect(childHwnd) {
        if (childHwnd == 0L) return@LaunchedEffect
        if (!initialized) {
            val initResult = player.initialize(childHwnd)
            println("MPV_SURFACE initialize($childHwnd)=$initResult (async init)")
            initialized = true
        }
    }

    // Reposition HWND after layout provides correct position/size
    LaunchedEffect(childHwnd, surfaceOffset, surfaceSize) {
        if (childHwnd == 0L) return@LaunchedEffect
        provider.setPos(
            x = surfaceOffset.x,
            y = surfaceOffset.y,
            width = surfaceSize.width.coerceAtLeast(1),
            height = surfaceSize.height.coerceAtLeast(1),
        )
    }

    // When fullscreen transitions occur, recreate the child HWND and update
    // mpv's wid. The parent window's style changes (WS_OVERLAPPEDWINDOW ↔
    // WS_POPUP) can orphan the child HWND's rendering surface. Forcing a new
    // HWND + setWid makes mpv recreate its video output from scratch.
    LaunchedEffect(fullscreenRevision) {
        if (!initialized || childHwnd == 0L) return@LaunchedEffect
        if (fullscreenRevision == 0) return@LaunchedEffect  // skip initial emission

        println("MPV_SURFACE fullscreenTransition revision=$fullscreenRevision player=${System.identityHashCode(player)}")

        // Get current playback position before detaching (seconds)
        val currentPosSec = runCatching {
            player.impl.getPropertyDouble("time-pos")
        }.getOrDefault(0.0)

        // First detach MPV from the old HWND so it doesn't try to render
        // to a window that will be orphaned by the parent style change.
        player.impl.command("set", "wid", "0")
        // Give mpv a moment to detach
        delay(30)

        val oldHwnd = childHwnd
        childHwnd = 0L

        // Create new child HWND parented to the (already restyled) Compose window
        val newHwnd = provider.create(
            x = surfaceOffset.x,
            y = surfaceOffset.y,
            width = surfaceSize.width.coerceAtLeast(320),
            height = surfaceSize.height.coerceAtLeast(240),
        )
        if (newHwnd == 0L) {
            println("MPV_SURFACE fullscreenTransition create failed, reverting to old HWND")
            childHwnd = oldHwnd
            // Try to reattach the old HWND after style change
            player.setWid(oldHwnd)
            return@LaunchedEffect
        }

        childHwnd = newHwnd

        // Attach MPV to the new child HWND; setWid handles VO reconfiguration
        val widResult = player.setWid(newHwnd)
        println("MPV_SURFACE setWid($newHwnd)=$widResult (fullscreenTransition)")

        if (widResult) {
            // Restore playback position if we had one
            if (currentPosSec > 0.0) {
                player.impl.command("set", "time-pos", currentPosSec.toString())
            }
        }

        // Destroy old HWND after a brief delay so MPV has time to switch
        if (oldHwnd != 0L && oldHwnd != newHwnd) {
            delay(50)
            provider.destroyHwnd(oldHwnd)
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onGloballyPositioned { coordinates: LayoutCoordinates ->
                val pos = coordinates.localToWindow(Offset.Zero)
                surfaceOffset = IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
            }
            .onSizeChanged { size: IntSize ->
                surfaceSize = size
            }
    )
}

@OptIn(InternalMediampApi::class)
@Composable
private fun LegacyGlSurface(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    val window = LocalWindow.current as ComposeWindow
    val fullscreenRevision = LocalWindowFullscreenRevision.current
    var currentSkiaLayer by remember { mutableStateOf(window.findSkiaLayer()) }

    LaunchedEffect(window) {
        while (true) {
            kotlinx.coroutines.delay(100)
            val newLayer = window.findSkiaLayer()
            if (newLayer !== currentSkiaLayer) {
                println("MPV_DESKTOP_SURFACE skiaLayerChanged old=${System.identityHashCode(currentSkiaLayer)} new=${System.identityHashCode(newLayer)}")
                currentSkiaLayer = newLayer
            }
        }
    }

    val components = remember(currentSkiaLayer) {
        currentSkiaLayer?.let { OpenGLComponentProvider(it) }
    }

    var textureId by remember(player) { mutableIntStateOf(0) }
    var renderContextInitialized by remember(player) { mutableStateOf(false) }
    var lastContextSignature by remember(player) { mutableStateOf<String?>(null) }
    var lastLoggedSurfaceSize by remember(player) { mutableStateOf<String?>(null) }
    var lastLoggedTextureSize by remember(player) { mutableStateOf<String?>(null) }
    var lastLoggedRenderFailure by remember(player) { mutableStateOf<String?>(null) }
    var lastLoggedReadPixels by remember(player) { mutableStateOf<String?>(null) }
    var lastLoggedMpvProps by remember(player) { mutableStateOf<String?>(null) }
    var pendingTextureSize by remember(player) { mutableStateOf<Size?>(null) }
    var hadSuccessfulRender by remember(player) { mutableStateOf(false) }
    var skipRenderRecovery by remember(player) { mutableStateOf(false) }
    val interpolator = remember(player) { FrameInterpolator() }
    val renderDebugMode = remember {
        System.getProperty("nuvio.mpv.render.debug")
            ?: System.getenv("NUVIO_MPV_RENDER_DEBUG")
            ?: ""
    }.lowercase()

    fun logSurface(message: String) {
        println("MPV_DESKTOP_SURFACE $message")
        runCatching {
            val logClass = Class.forName("com.nuvio.app.desktop.DesktopRuntimeLog")
            val logInstance = logClass.getField("INSTANCE").get(null)
            logClass.getMethod("info", String::class.java)
                .invoke(logInstance, "MPV_DESKTOP_SURFACE $message")
        }
    }

    fun releaseSkiaTextureResources() {
        player.image?.close()
        player.image = null
        player.backendTexture?.close()
        player.backendTexture = null
        textureId = 0
        player.currentSize = null
    }

    fun releaseTextureResources() {
        releaseSkiaTextureResources()
        runCatching { player.releaseTexture() }
        textureId = 0
        player.currentSize = null
    }

    fun createRenderContextIfNeeded(components: OpenGLComponentProvider): Boolean {
        if (renderContextInitialized && lastContextSignature == components.contextSignature) return true
        runCatching { components.directContext.resetGLAll() }
        val contextCreated = runCatching {
            player.createRenderContext(components.glDevice, components.glContext, components.glDrawable)
        }.getOrDefault(false)
        renderContextInitialized = contextCreated
        lastContextSignature = if (contextCreated) components.contextSignature else null
        logSurface(
            "renderContextCreate result=$contextCreated signature=${components.contextSignature} " +
                "player=${System.identityHashCode(player)}",
        )
        return contextCreated
    }

    fun recreateRenderContext(components: OpenGLComponentProvider, reason: String, surfaceSizeKey: String): Boolean {
        logSurface(
            "renderContextFullReset reason=$reason size=$surfaceSizeKey oldSignature=$lastContextSignature " +
                "newSignature=${components.contextSignature} texture=$textureId player=${System.identityHashCode(player)}",
        )
        releaseTextureResources()
        runCatching { player.releaseRenderContext() }
            .onFailure {
                logSurface(
                    "renderContextReleaseFailed reason=$reason size=$surfaceSizeKey " +
                        "error=${it::class.simpleName}:${it.message}",
                )
            }
        renderContextInitialized = false
        lastContextSignature = null
        runCatching { components.directContext.resetGLAll() }

        val contextCreated = runCatching {
            player.createRenderContext(components.glDevice, components.glContext, components.glDrawable)
        }.getOrDefault(false)
        renderContextInitialized = contextCreated
        lastContextSignature = if (contextCreated) components.contextSignature else null
        logSurface(
            "renderContextRecreate result=$contextCreated reason=$reason size=$surfaceSizeKey " +
                "signature=${components.contextSignature} player=${System.identityHashCode(player)}",
        )
        return contextCreated
    }

    DisposableEffect(components, player) {
        if (components == null) return@DisposableEffect onDispose { }

        onDispose {
            releaseTextureResources()
            player.releaseRenderContext()
            renderContextInitialized = false
            lastContextSignature = null
            textureId = 0
        }
    }

    LaunchedEffect(interpolator) {
        interpolator.frameLoop()
    }

    // When fullscreen transitions occur, force a full pipeline reset on the
    // LegacyGlSurface path (Linux/macOS). Without this, mpv's render context
    // can hold stale dimensions after the window resizes, producing a black
    // frame. Releasing all resources and resetting currentSize ensures the
    // Canvas recreates the texture and render context from scratch.
    LaunchedEffect(fullscreenRevision) {
        if (fullscreenRevision == 0) return@LaunchedEffect

        println("MPV_SURFACE_LINUX fullscreenTransition revision=$fullscreenRevision player=${System.identityHashCode(player)}")

        releaseTextureResources()
        runCatching { player.releaseRenderContext() }
        renderContextInitialized = false
        lastContextSignature = null
        textureId = 0
        player.currentSize = null
        pendingTextureSize = null
        hadSuccessfulRender = false
        runCatching { components?.directContext?.resetGLAll() }
    }

    Canvas(modifier = modifier) {
        interpolator.updateSubscription
        skipRenderRecovery = false

        if (components == null) return@Canvas
        if (player.getCurrentPlaybackState() == PlaybackState.DESTROYED) return@Canvas
        val skiaCanvas = drawContext.canvas.nativeCanvas
        val currentContextSignature = components.contextSignature
        val contentScale = components.contentScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val logicalWidth = size.width
        val logicalHeight = size.height
        val targetWidth = (logicalWidth * contentScale).roundToInt()
        val targetHeight = (logicalHeight * contentScale).roundToInt()
        val physicalSize = Size(targetWidth.toFloat(), targetHeight.toFloat())
        val surfaceSizeKey = "${logicalWidth.roundToInt()}x${logicalHeight.roundToInt()}@${contentScale}=${targetWidth}x$targetHeight"

        if (!renderContextInitialized) {
            if (!createRenderContextIfNeeded(components)) return@Canvas
        }

        if (lastContextSignature != null && lastContextSignature != currentContextSignature) {
            logSurface(
                "glContextChanged old=$lastContextSignature new=$currentContextSignature " +
                    "player=${System.identityHashCode(player)}",
            )
            recreateRenderContext(components, reason = "glContextChanged", surfaceSizeKey = surfaceSizeKey)
            if (!renderContextInitialized) return@Canvas
        } else {
            lastContextSignature = currentContextSignature
        }

        if (player.currentSize == null || player.currentSize != physicalSize || textureId == 0) {
            if (targetWidth <= 0 || targetHeight <= 0) {
                if (lastLoggedSurfaceSize != surfaceSizeKey) {
                    logSurface(
                        "ignoreZeroSize size=$surfaceSizeKey currentSize=${player.currentSize} " +
                            "player=${System.identityHashCode(player)}",
                    )
                    lastLoggedSurfaceSize = surfaceSizeKey
                }
                return@Canvas
            }

            if (player.currentSize != null && textureId != 0) {
                if (pendingTextureSize != physicalSize) {
                    pendingTextureSize = physicalSize
                    if (lastLoggedSurfaceSize != surfaceSizeKey) {
                        logSurface(
                            "surfaceSizeDeferred size=$surfaceSizeKey currentSize=${player.currentSize} " +
                                "textureId=$textureId player=${System.identityHashCode(player)}",
                        )
                        lastLoggedSurfaceSize = surfaceSizeKey
                    }
                    return@Canvas
                }
                pendingTextureSize = null
            }

            val previousSize = player.currentSize
            if (lastLoggedSurfaceSize != surfaceSizeKey) {
                logSurface(
                    "surfaceSizeChanged size=$surfaceSizeKey previous=$previousSize " +
                        "textureId=$textureId dpi=${components.currentDpi} signature=$currentContextSignature " +
                        "player=${System.identityHashCode(player)}",
                )
                lastLoggedSurfaceSize = surfaceSizeKey
            }

            val oldImage = player.image
            val oldBackendTexture = player.backendTexture
            val hadSize = player.currentSize
            val hadTextureId = textureId
            player.image = null
            player.backendTexture = null
            textureId = 0
            player.currentSize = null

            val hadTexture = hadSize != null && hadTextureId != 0
            val newTextureId = player.createTexture(targetWidth, targetHeight)

            if (newTextureId != 0) {
                val backendTexture = runCatching {
                    BackendTexture.makeGL(
                        width = targetWidth,
                        height = targetHeight,
                        isMipmapped = false,
                        textureId = newTextureId,
                        textureTarget = MpvMediampPlayer.GL_TEXTURE_2D,
                        textureFormat = MpvMediampPlayer.GL_RGBA8,
                    )
                }.getOrNull()
                if (backendTexture == null) {
                    player.currentSize = null
                    textureId = 0
                    oldImage?.close()
                    oldBackendTexture?.close()
                } else {
                    player.backendTexture = backendTexture
                    val adoptedImage = runCatching {
                        Image.adoptTextureFrom(
                            context = components.directContext,
                            backendTexture = backendTexture,
                            origin = SurfaceOrigin.TOP_LEFT,
                            colorType = ColorType.RGBA_8888,
                        )
                    }.getOrNull()
                    player.image = adoptedImage
                    if (adoptedImage == null) {
                        textureId = 0
                        player.currentSize = null
                        oldImage?.close()
                        oldBackendTexture?.close()
                        logSurface(
                            "textureAdoptFailed size=$surfaceSizeKey texture=$newTextureId " +
                                "player=${System.identityHashCode(player)}",
                        )
                        if (hadTexture) {
                            recreateRenderContext(components, reason = "textureAdoptFailedRecovery", surfaceSizeKey = surfaceSizeKey)
                        }
                    } else {
                        oldImage?.close()
                        oldBackendTexture?.close()
                        textureId = newTextureId
                        player.currentSize = physicalSize
                        skipRenderRecovery = true
                        if (lastLoggedTextureSize != surfaceSizeKey) {
                            logSurface(
                                "textureAllocated size=$surfaceSizeKey dpi=${components.currentDpi} texture=$textureId " +
                                    "signature=$currentContextSignature player=${System.identityHashCode(player)}",
                            )
                            lastLoggedTextureSize = surfaceSizeKey
                        }
                    }
                }
            } else {
                // Don't recreate context on initial texture creation failure —
                // mpv may not have a decoded frame yet, and createTexture can return
                // 0 transiently during startup. Only trigger recovery if we had a
                // previously working texture that now fails.
                if (hadTexture) {
                    recreateRenderContext(components, reason = "textureCreateFailedRecovery", surfaceSizeKey = surfaceSizeKey)
                } else {
                    player.currentSize = null
                    oldImage?.close()
                    oldBackendTexture?.close()
                    if (lastLoggedSurfaceSize != surfaceSizeKey) {
                        logSurface(
                            "textureCreateDeferred size=$surfaceSizeKey signature=$currentContextSignature " +
                                "player=${System.identityHashCode(player)}",
                        )
                        lastLoggedSurfaceSize = surfaceSizeKey
                    }
                }
            }
        }

        if (textureId != 0) {
            val renderResult = when (renderDebugMode) {
                "solid" -> runCatching {
                    player.debugRenderSolid(0.0f, 0.85f, 0.15f, 1.0f)
                }.getOrDefault(false)

                else -> runCatching { player.renderFrame() }
                    .getOrDefault(false)
            }
            runCatching { components.directContext.resetGLAll() }
            if (!renderResult) {
                val failureKey = "$surfaceSizeKey:$textureId:$currentContextSignature"
                if (lastLoggedRenderFailure != failureKey) {
                    logSurface(
                        "renderFrameFailed size=$surfaceSizeKey texture=$textureId " +
                            "signature=$currentContextSignature player=${System.identityHashCode(player)}",
                    )
                    lastLoggedRenderFailure = failureKey
                }
                if (hadSuccessfulRender && !skipRenderRecovery) {
                    runCatching { player.releaseTexture() }
                    releaseSkiaTextureResources()
                    recreateRenderContext(components, reason = "renderFrameRecovery", surfaceSizeKey = surfaceSizeKey)
                }
                return@Canvas
            }
            hadSuccessfulRender = true
            if (renderDebugMode == "readpixels") {
                val stats = runCatching { player.readTextureStats() }.getOrDefault("readTextureStatsFailed")
                if (lastLoggedReadPixels != stats) {
                    logSurface(
                        "readPixels stats=$stats mode=$renderDebugMode texture=$textureId " +
                            "player=${System.identityHashCode(player)}",
                    )
                    lastLoggedReadPixels = stats
                }
            }
            if (renderDebugMode.isNotBlank()) {
                val props = listOf(
                    "current-vo" to runCatching { player.impl.getPropertyString("current-vo") }.getOrDefault("<err>"),
                    "vid" to runCatching { player.impl.getPropertyString("vid") }.getOrDefault("<err>"),
                    "vo-configured" to runCatching { player.impl.getPropertyBoolean("vo-configured").toString() }.getOrDefault("<err>"),
                    "video-params/w" to runCatching { player.impl.getPropertyInt("video-params/w").toString() }.getOrDefault("<err>"),
                    "video-params/h" to runCatching { player.impl.getPropertyInt("video-params/h").toString() }.getOrDefault("<err>"),
                    "hwdec-current" to runCatching { player.impl.getPropertyString("hwdec-current") }.getOrDefault("<err>"),
                ).joinToString(separator = " ") { (key, value) -> "$key=$value" }
                val propsLogKey = "$surfaceSizeKey:$props"
                if (lastLoggedMpvProps != propsLogKey) {
                    logSurface(
                        "mpvProps size=$surfaceSizeKey texture=$textureId mode=$renderDebugMode $props " +
                            "player=${System.identityHashCode(player)}",
                    )
                    lastLoggedMpvProps = propsLogKey
                }
            }
        }
        player.image?.let {
            skiaCanvas.drawImageRect(it, Rect.makeWH(logicalWidth, logicalHeight))
        }
    }
}
