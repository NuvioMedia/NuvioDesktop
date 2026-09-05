package com.nuvio.app.features.trailer.desktop

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.nuvio.app.features.trailer.TrailerExtractionPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

internal class NativeMpvSurfacePlayer(
    val videoUrl: String,
    val audioUrl: String? = null,
    val startPositionMillis: Long = 0L,
    val playWhenReady: Boolean = true,
    initialMuted: Boolean = true,
    val fillFrame: Boolean = true,
    private val scope: CoroutineScope,
    private val onReady: () -> Unit,
    private val onEnded: () -> Unit,
    private val onError: () -> Unit,
) {
    private var handle: Long = 0L
    private val isDisposed = AtomicBoolean(false)
    private val lock = ReentrantReadWriteLock()

    private val _currentFrame = mutableStateOf<ImageBitmap?>(null)
    val currentFrame: State<ImageBitmap?> = _currentFrame

    private val _isReady = mutableStateOf(false)
    val isReady: State<Boolean> = _isReady

    private var terminalReported = false
    private var renderJob: Job? = null

    // Double buffering for Skia bitmaps
    private val skiaBitmaps = arrayOfNulls<Bitmap>(2)
    private var currentBitmapWidth = 0
    private var currentBitmapHeight = 0
    private var nextBitmapIndex = 0

    init {
        TrailerExtractionPlatform.diagnostic(
            "NativeMpvSurfacePlayer init video=${TrailerExtractionPlatform.describeUrl(videoUrl)} " +
                "separateAudio=${!audioUrl.isNullOrBlank()} startMs=$startPositionMillis fillFrame=$fillFrame"
        )
        try {
            handle = NativeMpvSurfaceBridge.nativeCreate(
                videoUrl = videoUrl,
                audioUrl = audioUrl,
                startPositionMs = startPositionMillis,
                playWhenReady = playWhenReady,
                muted = initialMuted,
                fillFrame = fillFrame,
            )
            if (handle == 0L) {
                TrailerExtractionPlatform.diagnostic("NativeMpvSurfacePlayer failed to create native handle")
                onError()
            } else {
                startRenderLoop()
            }
        } catch (e: Throwable) {
            TrailerExtractionPlatform.diagnostic("NativeMpvSurfacePlayer create error: ${e.message}")
            onError()
        }
    }

    private fun startRenderLoop() {
        renderJob = scope.launch(Dispatchers.Default) {
            while (isActive && !isDisposed.get()) {
                val currentHandle = handle
                if (currentHandle == 0L) break

                if (NativeMpvSurfaceBridge.nativeHasError(currentHandle)) {
                    if (!terminalReported) {
                        terminalReported = true
                        TrailerExtractionPlatform.diagnostic("NativeMpvSurfacePlayer reported native error")
                        onError()
                    }
                    break
                }

                renderCurrentFrame(currentHandle)

                if (!_isReady.value && _currentFrame.value != null && NativeMpvSurfaceBridge.nativeIsReady(currentHandle)) {
                    _isReady.value = true
                    TrailerExtractionPlatform.diagnostic("NativeMpvSurfacePlayer is ready with first frame")
                    onReady()
                }

                if (NativeMpvSurfaceBridge.nativeIsEnded(currentHandle)) {
                    if (!terminalReported) {
                        terminalReported = true
                        TrailerExtractionPlatform.diagnostic("NativeMpvSurfacePlayer ended")
                        onEnded()
                    }
                }

                delay(16) // ~60fps cadence
            }
        }
    }

    fun setSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || isDisposed.get()) return
        // Clamp to 1080p maximum to prevent excessive buffer allocation on high DPI displays
        val targetWidth = width.coerceIn(64, 1920)
        val targetHeight = height.coerceIn(64, 1080)

        lock.write {
            if (currentBitmapWidth != targetWidth || currentBitmapHeight != targetHeight) {
                currentBitmapWidth = targetWidth
                currentBitmapHeight = targetHeight
                val imageInfo = ImageInfo(
                    ColorInfo(ColorType.BGRA_8888, ColorAlphaType.OPAQUE, ColorSpace.sRGB),
                    targetWidth,
                    targetHeight,
                )
                for (i in skiaBitmaps.indices) {
                    skiaBitmaps[i] = Bitmap().apply { allocPixels(imageInfo) }
                }
                nextBitmapIndex = 0
            }
        }
    }

    private fun renderCurrentFrame(currentHandle: Long) {
        if (isDisposed.get() || currentHandle == 0L) return
        var targetBitmap: Bitmap? = null
        var pixelsAddr = 0L
        var width = 0
        var height = 0

        lock.write {
            width = currentBitmapWidth
            height = currentBitmapHeight
            if (width > 0 && height > 0 && skiaBitmaps[0] != null) {
                val bmp = skiaBitmaps[nextBitmapIndex]
                val pixmap = bmp?.peekPixels()
                if (pixmap != null && pixmap.addr != 0L) {
                    targetBitmap = bmp
                    pixelsAddr = pixmap.addr
                    nextBitmapIndex = (nextBitmapIndex + 1) % skiaBitmaps.size
                }
            }
        }

        if (pixelsAddr != 0L && targetBitmap != null) {
            val hasNewFrame = NativeMpvSurfaceBridge.nativeRenderFrame(currentHandle, pixelsAddr, width, height)
            if (hasNewFrame) {
                if (!_isReady.value) {
                    _isReady.value = true
                    onReady()
                }
                _currentFrame.value = targetBitmap.asComposeImageBitmap()
            }
        }
    }

    fun setMuted(muted: Boolean) {
        val currentHandle = handle
        if (!isDisposed.get() && currentHandle != 0L) {
            NativeMpvSurfaceBridge.nativeSetMuted(currentHandle, muted)
        }
    }

    fun setPaused(paused: Boolean) {
        val currentHandle = handle
        if (!isDisposed.get() && currentHandle != 0L) {
            NativeMpvSurfaceBridge.nativeSetPaused(currentHandle, paused)
        }
    }

    fun dispose() {
        if (isDisposed.compareAndSet(false, true)) {
            renderJob?.cancel()
            val currentHandle = handle
            handle = 0L
            if (currentHandle != 0L) {
                NativeMpvSurfaceBridge.nativeDispose(currentHandle)
            }
            lock.write {
                _currentFrame.value = null
                for (i in skiaBitmaps.indices) {
                    skiaBitmaps[i] = null
                }
            }
        }
    }
}
