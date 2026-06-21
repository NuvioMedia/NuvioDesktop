package com.nuvio.app.features.player.desktop

import java.awt.Canvas
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.roundToLong

internal class NativePlayerHost : Canvas() {
    var onPeerReady: (() -> Unit)? = null
    var onDisplayableChanged: ((Boolean) -> Unit)? = null
    var onFirstPaint: (() -> Unit)? = null
    var onFirstFullSizePaint: (() -> Unit)? = null
    var onCursorActivity: (() -> Unit)? = null
    var onSurfacePointerActivity: (() -> Unit)? = null
    var onSurfacePrimaryClick: (() -> Unit)? = null
    var onSurfaceDoubleClick: (() -> Unit)? = null
    var onSurfaceHorizontalDragSeek: ((Long) -> Unit)? = null
    private var firstPaintNotified = false
    private var firstFullSizePaintNotified = false
    private var controlsVisible = true
    private var cursorVisible = true
    private var cursorHideTimer: Timer? = null
    private var surfaceClickTimer: Timer? = null
    private var suppressSurfaceClickTimer: Timer? = null
    private var surfaceDragStartX = 0
    private var surfaceDragStartY = 0
    private var surfaceDragCurrentX = 0
    private var surfaceDragSeekActive = false
    private var surfaceDragSeekTimer: Timer? = null
    private var suppressNextSurfaceClick = false

    private companion object {
        const val CursorIdleHideDelayMs = 3_000
        const val SurfaceSingleClickDelayMs = 220
        const val SurfaceSuppressClickDelayMs = 350
        const val SurfaceDragSeekStartThresholdPx = 12.0
        const val SurfaceDragSeekMaxStepMs = 30_000L
        const val SurfaceDragSeekTickMs = 120

        val hiddenCursor: Cursor by lazy {
            val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "nuvio-hidden-cursor")
        }
    }

    init {
        background = Color.BLACK
        ignoreRepaint = false
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    beginSurfaceDragSeek(event)
                    noteCursorActivity()
                }
            }

            override fun mouseReleased(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event) && finishSurfaceDragSeek()) {
                    event.consume()
                }
            }

            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                noteCursorActivity()
                if (suppressNextSurfaceClick) {
                    suppressNextSurfaceClick = false
                    cancelSuppressSurfaceClickTimer()
                    return
                }
                if (event.clickCount >= 2) {
                    cancelSurfaceClickTimer()
                    onSurfaceDoubleClick?.invoke()
                } else {
                    restartSurfaceClickTimer()
                }
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                noteCursorActivity()
            }

            override fun mouseDragged(event: MouseEvent) {
                noteCursorActivity()
                if ((event.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK) != 0 && updateSurfaceDragSeek(event)) {
                    event.consume()
                }
            }
        })
    }

    fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible) return
        controlsVisible = visible
        cancelCursorHideTimer()
        setCursorVisible(visible)
    }

    fun noteCursorActivity() {
        onCursorActivity?.invoke()
        if (controlsVisible) {
            cancelCursorHideTimer()
            setCursorVisible(true)
            onSurfacePointerActivity?.invoke()
            return
        }
        setCursorVisible(true)
        restartCursorHideTimer()
        onSurfacePointerActivity?.invoke()
    }

    fun resetCursorVisibility() {
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

    private fun restartSurfaceClickTimer() {
        cancelSurfaceClickTimer()
        surfaceClickTimer = Timer(SurfaceSingleClickDelayMs) {
            onSurfacePrimaryClick?.invoke()
            cancelSurfaceClickTimer()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun cancelSurfaceClickTimer() {
        surfaceClickTimer?.stop()
        surfaceClickTimer = null
    }

    private fun beginSurfaceDragSeek(event: MouseEvent) {
        surfaceDragStartX = event.x
        surfaceDragStartY = event.y
        surfaceDragCurrentX = event.x
        surfaceDragSeekActive = false
        stopSurfaceDragSeekTimer()
    }

    private fun updateSurfaceDragSeek(event: MouseEvent): Boolean {
        val totalX = (event.x - surfaceDragStartX).toDouble()
        val totalY = (event.y - surfaceDragStartY).toDouble()
        surfaceDragCurrentX = event.x
        if (!surfaceDragSeekActive) {
            val horizontalEnough = abs(totalX) >= SurfaceDragSeekStartThresholdPx
            val horizontalIntent = abs(totalX) >= abs(totalY) * 1.1
            if (!horizontalEnough || !horizontalIntent) return false
            surfaceDragSeekActive = true
            suppressNextSurfaceClickBriefly()
            cancelSurfaceClickTimer()
            startSurfaceDragSeekTimer()
        }
        return true
    }

    private fun finishSurfaceDragSeek(): Boolean {
        val wasActive = surfaceDragSeekActive
        surfaceDragSeekActive = false
        stopSurfaceDragSeekTimer()
        if (wasActive) {
            suppressNextSurfaceClickBriefly()
            cancelSurfaceClickTimer()
        }
        return wasActive
    }

    private fun startSurfaceDragSeekTimer() {
        if (surfaceDragSeekTimer != null) return
        tickSurfaceDragSeek()
        surfaceDragSeekTimer = Timer(SurfaceDragSeekTickMs) {
            tickSurfaceDragSeek()
        }.apply {
            start()
        }
    }

    private fun stopSurfaceDragSeekTimer() {
        surfaceDragSeekTimer?.stop()
        surfaceDragSeekTimer = null
    }

    private fun tickSurfaceDragSeek() {
        if (!surfaceDragSeekActive) return
        val deltaMs = surfaceDragSeekDeltaForDistance((surfaceDragCurrentX - surfaceDragStartX).toDouble())
        if (deltaMs != 0L) {
            onSurfaceHorizontalDragSeek?.invoke(deltaMs)
        }
    }

    private fun surfaceDragSeekDeltaForDistance(totalX: Double): Long {
        val distancePastThreshold = (abs(totalX) - SurfaceDragSeekStartThresholdPx).coerceAtLeast(0.0)
        if (distancePastThreshold < 1.0) return 0L
        val speedMsPerSecond = (Math.pow(distancePastThreshold / 80.0, 1.25) * 12_000.0)
            .coerceIn(1_500.0, 60_000.0)
        val deltaMs = (speedMsPerSecond * SurfaceDragSeekTickMs / 1000.0)
            .roundToLong()
            .coerceIn(0L, SurfaceDragSeekMaxStepMs)
        return if (totalX < 0.0) -deltaMs else deltaMs
    }

    private fun suppressNextSurfaceClickBriefly() {
        suppressNextSurfaceClick = true
        cancelSuppressSurfaceClickTimer()
        suppressSurfaceClickTimer = Timer(SurfaceSuppressClickDelayMs) {
            suppressNextSurfaceClick = false
            cancelSuppressSurfaceClickTimer()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun cancelSuppressSurfaceClickTimer() {
        suppressSurfaceClickTimer?.stop()
        suppressSurfaceClickTimer = null
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
        onSurfaceHorizontalDragSeek = null
        resetCursorVisibility()
        cancelSurfaceClickTimer()
        cancelSuppressSurfaceClickTimer()
        stopSurfaceDragSeekTimer()
        super.removeNotify()
    }
}
