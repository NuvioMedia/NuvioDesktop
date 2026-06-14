/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.openani.mediamp.mpv.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.window.LocalWindow
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SurfaceOrigin
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.utils.OpenGLComponentProvider
import org.openani.mediamp.mpv.utils.findSkiaLayer
import kotlin.math.roundToInt

@OptIn(InternalMediampApi::class)
@Composable
actual fun MpvMediampPlayerSurface(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    val window = LocalWindow.current as ComposeWindow
    val components = remember(window) {
        window.findSkiaLayer()?.let { OpenGLComponentProvider(it) }
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

    // Bind the render context to BOTH the GL components and the player. When
    // the upstream wrapper recreates the player (source / episode switch keyed
    // on the stream identity) the components instance can be the same window,
    // so keying only on `components` would skip the render-context creation
    // for the new player and leave it rendering nowhere. Keying on the player
    // ensures every fresh MpvMediampPlayer gets its own render context bound
    // to the current SkiaLayer GL device/context.
    DisposableEffect(components, player) {
        if (components == null) return@DisposableEffect onDispose { }

        // On Linux, GL context is NOT current during composition phase. The
        // Canvas callback below is where OpenGL context is bound to the thread,
        // so we defer context creation to the first Canvas frame instead.
        // On Windows, wglMakeCurrent tolerates being called without a current
        // context, so the Canvas path also works there.

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

    Canvas(modifier = modifier) {
        interpolator.updateSubscription

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

            // Defer texture recreation when the size is still changing — going
            // fullscreen can trigger 3+ rapid resize events and recreating the
            // texture on each one produces visible black flicker.
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

            // Keep old Skia wrappers alive during texture recreation so
            // drawImageRect below never sees null (which would produce a
            // black frame). Close them only after the new texture is fully
            // adopted.
            val oldImage = player.image
            val oldBackendTexture = player.backendTexture
            player.image = null
            player.backendTexture = null
            textureId = 0
            player.currentSize = null

            // Keep libmpv's render context alive on normal size changes. The
            // render API documents that freeing an active render context
            // disables video; resize only needs a fresh GL render target.
            runCatching { components.directContext.resetGLAll() }

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
                    } else {
                        // Close old resources only after new ones are ready
                        oldImage?.close()
                        oldBackendTexture?.close()
                        textureId = newTextureId
                        player.currentSize = physicalSize
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
                // Texture creation failed — leave currentSize null so the next
                // frame retries instead of getting stuck rendering nothing.
                player.currentSize = null
                oldImage?.close()
                oldBackendTexture?.close()
                logSurface(
                    "textureCreateFailed size=$surfaceSizeKey signature=$currentContextSignature " +
                        "player=${System.identityHashCode(player)}",
                )
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
            // Always reset GL state after renderFrame(), even on failure, to
            // prevent corrupted GL state from triggering a SIGSEGV in Skiko's
            // GrDirectContext::resetContext on the next frame.
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
                return@Canvas
            }
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
