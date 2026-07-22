package com.nuvio.app.features.player.desktop

import java.awt.Canvas
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

internal class NativePlayerHost : Canvas() {
    var onPeerReady: (() -> Unit)? = null
    var onBeforeRemoveNotify: (() -> Unit)? = null
    var onDisplayableChanged: ((Boolean) -> Unit)? = null
    var onFirstPaint: (() -> Unit)? = null
    var onFirstFullSizePaint: (() -> Unit)? = null
    var onCursorActivity: (() -> Unit)? = null
    private var firstPaintNotified = false
    private var firstFullSizePaintNotified = false
    private var controlsVisible = true
    private var cursorVisible = true

    private companion object {
        val hiddenCursor: Cursor by lazy {
            val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "nuvio-hidden-cursor")
        }
    }

    init {
        background = Color.BLACK
        ignoreRepaint = false
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                noteCursorActivity()
            }

            override fun mouseDragged(event: MouseEvent) {
                noteCursorActivity()
            }
        })
        if (DesktopHostOs.current == DesktopHostOs.LINUX) {
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    notifyReadyIfSized()
                }

                override fun componentShown(event: ComponentEvent) {
                    notifyReadyIfSized()
                }
            })
        }
    }

    private fun notifyReadyIfSized() {
        if (firstFullSizePaintNotified || width <= 1 || height <= 1) return
        SwingUtilities.invokeLater {
            if (!isDisplayable || firstFullSizePaintNotified || width <= 1 || height <= 1) return@invokeLater
            if (!firstPaintNotified) {
                firstPaintNotified = true
                onFirstPaint?.invoke()
            }
            firstFullSizePaintNotified = true
            onFirstFullSizePaint?.invoke()
        }
    }

    fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        setCursorVisible(visible)
    }

    fun noteCursorActivity() {
        onCursorActivity?.invoke()
    }

    fun resetCursorVisibility() {
        controlsVisible = true
        setCursorVisible(true)
    }

    private fun setCursorVisible(visible: Boolean) {
        if (cursorVisible == visible) return
        cursorVisible = visible
        cursor = if (visible) Cursor.getDefaultCursor() else hiddenCursor
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
        if (DesktopHostOs.current == DesktopHostOs.LINUX) {
            SwingUtilities.invokeLater {
                if (!isDisplayable || firstPaintNotified) return@invokeLater
                firstPaintNotified = true
                onFirstPaint?.invoke()
                notifyReadyIfSized()
            }
        }
    }

    override fun removeNotify() {
        onBeforeRemoveNotify?.invoke()
        onDisplayableChanged?.invoke(false)
        firstPaintNotified = false
        firstFullSizePaintNotified = false
        onPeerReady = null
        onBeforeRemoveNotify = null
        onFirstPaint = null
        onFirstFullSizePaint = null
        resetCursorVisibility()
        super.removeNotify()
    }
}
