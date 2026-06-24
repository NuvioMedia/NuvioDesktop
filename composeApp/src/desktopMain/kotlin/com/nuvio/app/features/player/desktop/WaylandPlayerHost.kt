package com.nuvio.app.features.player.desktop

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.Cursor
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.image.BufferedImage
import javax.swing.Timer

/**
 * PlayerHost for Linux Wayland sessions where mpv cannot render directly
 * into an X11 window (no wid support). Instead, mpv renders offscreen
 * (EGL FBO via GBM) and this host pulls finished frames via
 * [NativePlayerBridge.renderFrameBytes] into a Skia [Image] that
 * Compose Canvas draws each tick. Double-buffered byte arrays avoid
 * per-frame allocation overhead.
 */
internal class WaylandPlayerHost : PlayerHost {
    @Volatile
    override var nativeHandle: Long = 0L

    override var onMouseClick: (() -> Unit)? = null
    override var onDoubleClick: (() -> Unit)? = null
    override var onCursorActivity: (() -> Unit)? = null

    private var lastWidth = 0
    private var lastHeight = 0

    /* Double-buffer: one being drawn by Compose, other being filled by native. */
    private var bufferA: ByteArray? = null
    private var bufferB: ByteArray? = null
    private var useBufferA = true

    var latestImage: Image? = null
        private set

    private var controlsVisible = true
    private var cursorVisible = true
    private var cursorHideTimer: Timer? = null

    fun renderFrame(width: Int, height: Int): Boolean {
        val handle = nativeHandle
        if (handle == 0L || width <= 0 || height <= 0) return false

        val byteCount = width * height * 4

        // Use double-buffer: write to inactive buffer while Compose reads from active one
        val bytes: ByteArray
        if (useBufferA) {
            if (bufferA == null || bufferA!!.size < byteCount) bufferA = ByteArray(byteCount)
            bytes = bufferA!!
        } else {
            if (bufferB == null || bufferB!!.size < byteCount) bufferB = ByteArray(byteCount)
            bytes = bufferB!!
        }
        useBufferA = !useBufferA

        if (!NativePlayerBridge.renderFrameBytes(handle, bytes, width, height)) return false
        if (nativeHandle == 0L) return false

        val imageInfo = ImageInfo.makeS32(width, height, ColorAlphaType.UNPREMUL)
        val previousImage = latestImage
        latestImage = Image.makeRaster(imageInfo, bytes, width * 4)
        previousImage?.close()
        lastWidth = width
        lastHeight = height
        return true
    }

    override fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible) return
        controlsVisible = visible
        cancelCursorHideTimer()
        setCursorVisible(visible)
    }

    override fun noteCursorActivity() {
        onCursorActivity?.invoke()
        if (controlsVisible) {
            cancelCursorHideTimer()
            setCursorVisible(true)
            return
        }
        setCursorVisible(true)
        restartCursorHideTimer()
    }

    override fun resetCursorVisibility() {
        controlsVisible = true
        cancelCursorHideTimer()
        setCursorVisible(true)
    }

    override fun dispose() {
        resetCursorVisibility()
        latestImage?.close()
        latestImage = null
        bufferA = null
        bufferB = null
    }

    private fun setCursorVisible(visible: Boolean) {
        if (cursorVisible == visible) return
        cursorVisible = visible
        val window = activeWindow ?: return
        window.cursor = if (visible) Cursor.getDefaultCursor() else hiddenCursor
    }

    private fun restartCursorHideTimer() {
        cancelCursorHideTimer()
        cursorHideTimer = Timer(CursorIdleHideDelayMs) {
            if (!controlsVisible) {
                setCursorVisible(false)
            }
            cancelCursorHideTimer()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun cancelCursorHideTimer() {
        cursorHideTimer?.stop()
        cursorHideTimer = null
    }

    private val activeWindow: Window?
        get() = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?: Window.getWindows().firstOrNull { it.isVisible && it.isActive }

    private companion object {
        const val CursorIdleHideDelayMs = 3_000
        val hiddenCursor: Cursor by lazy {
            val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "nuvio-hidden-cursor")
        }
    }
}
