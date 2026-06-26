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

internal class NativePlayerHost : Canvas(), PlayerHost {
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

    @Volatile
    override var nativeHandle: Long = 0L

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
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, width, height)
        if (!firstPaintNotified) {
            firstPaintNotified = true
            onFirstPaint?.invoke()
        }
        if (!firstFullSizePaintNotified && width > 1 && height > 1) {
            firstFullSizePaintNotified = true
            onFirstFullSizePaint?.invoke()
        }
    }

    override fun addNotify() {
        super.addNotify()
        onDisplayableChanged?.invoke(true)
        repaint()
        onPeerReady?.invoke()
    }

    override fun removeNotify() {
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
