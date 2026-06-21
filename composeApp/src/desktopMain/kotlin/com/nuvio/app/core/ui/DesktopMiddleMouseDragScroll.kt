package com.nuvio.app.core.ui

import java.awt.AWTEvent
import java.awt.Component
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.truncate

private const val DragStartThresholdPx = 3.0
private const val PixelsPerWheelStep = 18.0
private const val WheelScrollAmount = 3

fun installDesktopMiddleMouseDragScroll(window: Window): () -> Unit {
    val handler = DesktopMiddleMouseDragScroll(window)
    handler.install()
    return handler::uninstall
}

private class DesktopMiddleMouseDragScroll(
    private val window: Window,
) {
    private var installed = false
    private var dragTarget: Component? = null
    private var sourceComponent: Component? = null
    private var previousCursor: Cursor? = null
    private var lastYOnScreen = 0
    private var accumulatedPixels = 0.0
    private var dragStarted = false

    private val listener = AWTEventListener { event ->
        val mouseEvent = event as? MouseEvent ?: return@AWTEventListener
        when (mouseEvent.id) {
            MouseEvent.MOUSE_PRESSED -> handlePressed(mouseEvent)
            MouseEvent.MOUSE_DRAGGED -> handleDragged(mouseEvent)
            MouseEvent.MOUSE_RELEASED -> handleReleased(mouseEvent)
        }
    }

    fun install() {
        if (installed) return
        Toolkit.getDefaultToolkit().addAWTEventListener(
            listener,
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK,
        )
        installed = true
    }

    fun uninstall() {
        if (!installed) return
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        finishDrag()
        installed = false
    }

    private fun handlePressed(event: MouseEvent) {
        if (event.button != MouseEvent.BUTTON2) return
        val component = event.componentOrNull() ?: return
        if (!component.belongsToWindow(window) || component.isNativePlayerSurface()) return

        sourceComponent = component
        dragTarget = component.targetAt(event) ?: component
        previousCursor = window.cursor
        window.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        lastYOnScreen = event.yOnScreen
        accumulatedPixels = 0.0
        dragStarted = false
        event.consume()
    }

    private fun handleDragged(event: MouseEvent) {
        val target = dragTarget ?: return
        if ((event.modifiersEx and MouseEvent.BUTTON2_DOWN_MASK) == 0) {
            finishDrag()
            return
        }
        val component = event.componentOrNull() ?: sourceComponent ?: return
        if (!component.belongsToWindow(window) || component.isNativePlayerSurface()) {
            finishDrag()
            return
        }

        val deltaY = event.yOnScreen - lastYOnScreen
        lastYOnScreen = event.yOnScreen
        if (deltaY == 0) {
            event.consume()
            return
        }

        accumulatedPixels += deltaY.toDouble()
        if (!dragStarted && abs(accumulatedPixels) < DragStartThresholdPx) {
            event.consume()
            return
        }
        dragStarted = true

        val wheelSteps = truncate(accumulatedPixels / PixelsPerWheelStep).toInt()
        if (wheelSteps != 0) {
            accumulatedPixels -= wheelSteps * PixelsPerWheelStep
            dispatchWheel(event, target, wheelSteps)
        }
        event.consume()
    }

    private fun handleReleased(event: MouseEvent) {
        if (event.button == MouseEvent.BUTTON2 || dragTarget != null) {
            event.consume()
            finishDrag()
        }
    }

    private fun finishDrag() {
        dragTarget = null
        sourceComponent = null
        accumulatedPixels = 0.0
        dragStarted = false
        window.cursor = previousCursor ?: Cursor.getDefaultCursor()
        previousCursor = null
    }

    private fun dispatchWheel(event: MouseEvent, target: Component, wheelSteps: Int) {
        val source = event.componentOrNull() ?: target
        val targetPoint = SwingUtilities.convertPoint(source, event.point, target)
        val wheelEvent = MouseWheelEvent(
            target,
            MouseEvent.MOUSE_WHEEL,
            event.`when`,
            event.modifiersEx,
            targetPoint.x,
            targetPoint.y,
            event.xOnScreen,
            event.yOnScreen,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            WheelScrollAmount,
            wheelSteps,
            wheelSteps.toDouble(),
        )
        target.dispatchEvent(wheelEvent)
    }

    private fun Component.targetAt(event: MouseEvent): Component? {
        val windowPoint = SwingUtilities.convertPoint(this, event.point, window)
        return SwingUtilities.getDeepestComponentAt(window, windowPoint.x, windowPoint.y)
    }

    private fun MouseEvent.componentOrNull(): Component? = component ?: source as? Component

    private fun Component.belongsToWindow(targetWindow: Window): Boolean =
        this == targetWindow || SwingUtilities.getWindowAncestor(this) == targetWindow

    private fun Component.isNativePlayerSurface(): Boolean {
        var current: Component? = this
        while (current != null) {
            if (current.javaClass.name == "com.nuvio.app.features.player.desktop.NativePlayerHost") {
                return true
            }
            current = current.parent
        }
        return false
    }
}
