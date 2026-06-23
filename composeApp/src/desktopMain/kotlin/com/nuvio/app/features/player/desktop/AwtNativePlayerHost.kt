package com.nuvio.app.features.player.desktop

import java.awt.Canvas
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.Timer

private val usesSoftwareRendering: Boolean
    get() = DesktopHostOs.current == DesktopHostOs.LINUX

internal class AwtNativePlayerHost : Canvas(), PlayerHost {
    var onPeerReady: (() -> Unit)? = null
    var onDisplayableChanged: ((Boolean) -> Unit)? = null
    var onFirstPaint: (() -> Unit)? = null
    var onFirstFullSizePaint: (() -> Unit)? = null
    var onResize: ((width: Int, height: Int) -> Unit)? = null
    private var firstPaintNotified = false
    private var firstFullSizePaintNotified = false
    override var onMouseClick: (() -> Unit)? = null
    override var onCursorActivity: (() -> Unit)? = null
    private var controlsVisible = true
    private var cursorVisible = true
    private var cursorHideTimer: Timer? = null

    private var renderTimer: Timer? = null
    private var pixelBuffer: IntArray? = null
    private var frameImage: BufferedImage? = null

    @Volatile
    override var nativeHandle: Long = 0L
        set(value) {
            field = value
            if (value != 0L && usesSoftwareRendering) {
                startRenderTimer()
            } else {
                stopRenderTimer()
            }
        }

    private fun startRenderTimer() {
        if (renderTimer != null) return
        renderTimer = Timer(33) {
            if (nativeHandle != 0L && isDisplayable) {
                repaint()
            } else {
                stopRenderTimer()
            }
        }.apply {
            isRepeats = true
            start()
        }
    }

    private fun stopRenderTimer() {
        renderTimer?.stop()
        renderTimer = null
    }

    private companion object {
        const val CursorIdleHideDelayMs = 3_000

        val hiddenCursor: Cursor by lazy {
            val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "nuvio-hidden-cursor")
        }
    }

    init {
        background = Color.BLACK
        ignoreRepaint = false
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                onResize?.invoke(width, height)
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                noteCursorActivity()
                onMouseClick?.invoke()
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                noteCursorActivity()
            }

            override fun mouseDragged(event: MouseEvent) {
                noteCursorActivity()
            }
        })
    }

    override fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible) return
        controlsVisible = visible
        cancelCursorHideTimer()
        setCursorVisible(visible)
    }

    override fun noteCursorActivity() {
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

    private fun setCursorVisible(visible: Boolean) {
        if (cursorVisible == visible) return
        cursorVisible = visible
        cursor = if (visible) Cursor.getDefaultCursor() else hiddenCursor
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

    override fun update(graphics: Graphics) {
        paint(graphics)
    }

    override fun paint(graphics: Graphics) {
        if (nativeHandle != 0L && !usesSoftwareRendering) {
            // On Windows/macOS mpv renders directly into its child HWND;
            // do not paint over it.
        } else {
            graphics.color = Color.BLACK
            graphics.fillRect(0, 0, width, height)
            if (nativeHandle != 0L && width > 0 && height > 0) {
                val needed = width * height
                val buf = pixelBuffer
                if (buf == null || buf.size < needed) {
                    pixelBuffer = IntArray(needed)
                }
                pixelBuffer?.let { pix ->
                    if (NativePlayerBridge.renderFrame(nativeHandle, pix, width, height)) {
                        val img = frameImage
                        if (img == null || img.width != width || img.height != height) {
                            BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
                                frameImage = it
                            }
                        }
                        frameImage?.setRGB(0, 0, width, height, pix, 0, width)
                    }
                }
                frameImage?.let { graphics.drawImage(it, 0, 0, null) }
            }
        }
        if (!firstPaintNotified) {
            firstPaintNotified = true
            System.err.println("[NUVIO_HOST] first paint w=$width h=$height")
            onFirstPaint?.invoke()
        }
        if (!firstFullSizePaintNotified && width > 1 && height > 1) {
            firstFullSizePaintNotified = true
            System.err.println("[NUVIO_HOST] first full-size paint w=$width h=$height")
            onFirstFullSizePaint?.invoke()
        }
    }

    override fun addNotify() {
        super.addNotify()
        System.err.println("[NUVIO_HOST] addNotify() w=$width h=$height")
        onDisplayableChanged?.invoke(true)
        if (!firstFullSizePaintNotified && width > 1 && height > 1) {
            firstFullSizePaintNotified = true
            System.err.println("[NUVIO_HOST] addNotify: firstFullSizePaintNotified=true")
            onFirstFullSizePaint?.invoke()
        }
        repaint()
        onPeerReady?.invoke()
    }

    override fun removeNotify() {
        System.err.println("[NUVIO_HOST] removeNotify()")
        stopRenderTimer()
        nativeHandle = 0L
        onDisplayableChanged?.invoke(false)
        firstPaintNotified = false
        firstFullSizePaintNotified = false
        onPeerReady = null
        onFirstPaint = null
        onFirstFullSizePaint = null
        resetCursorVisibility()
        super.removeNotify()
    }
}
