package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Shows the hand cursor while the mouse is over anything clickable in [window].
 *
 * Compose never switches the cursor for clickable content on its own, and the app has hundreds of click
 * targets, so this looks up what is under the pointer in the semantics tree instead of touching each one.
 */
@Composable
internal fun ClickableHandCursor(window: ComposeWindow) {
    DisposableEffect(window) {
        val controller = HandCursorController(window)
        Toolkit.getDefaultToolkit().addAWTEventListener(controller, HandCursorEventMask)
        onDispose {
            Toolkit.getDefaultToolkit().removeAWTEventListener(controller)
            controller.dispose()
        }
    }
}

private const val HandCursorEventMask =
    AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK

internal class HandCursorController(private val window: ComposeWindow) : AWTEventListener {
    private val handCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    private var overridden: Component? = null
    private var cursorBeforeOverride: Cursor? = null
    private var pending: MouseEvent? = null
    private var invokeLaterScheduled: Boolean = false
    private var pendingForce: Boolean = false
    private var hasWheelEvent: Boolean = false
    private var lastWheelEventNanos: Long = 0L
    private var lastLookupNanos: Long = 0L
    private var lastCheckedSource: Component? = null
    private var lastCheckedPoint: Point? = null
    private var disposed: Boolean = false
    private var scheduledFireNanos: Long = 0L
    private val deferredUpdateTimer = Timer(WheelIdleRefreshDelayMs) {
        scheduledFireNanos = 0L
        update(force = true)
    }.apply {
        isRepeats = false
    }

    private fun scheduleDeferredUpdate(delayMs: Int, now: Long) {
        val clampedDelayMs = delayMs.coerceAtLeast(1)
        val targetFireNanos = now + clampedDelayMs * 1_000_000L
        if (deferredUpdateTimer.isRunning && targetFireNanos <= scheduledFireNanos) return
        scheduledFireNanos = targetFireNanos
        deferredUpdateTimer.initialDelay = clampedDelayMs
        deferredUpdateTimer.restart()
    }

    override fun eventDispatched(event: AWTEvent) {
        if (disposed) return
        val mouseEvent = event as? MouseEvent ?: return
        val source = mouseEvent.component ?: return
        if (source !== window && !SwingUtilities.isDescendingFrom(source, window)) return

        val now = System.nanoTime()
        if (mouseEvent is MouseWheelEvent) {
            // Never walk the Compose semantics tree on the EDT during active wheel scrolling;
            // refresh once after wheel input pauses.
            hasWheelEvent = true
            lastWheelEventNanos = now
            pending = mouseEvent
            pendingForce = true
            scheduleDeferredUpdate(WheelIdleRefreshDelayMs, now)
            return
        }

        val isMotion = mouseEvent.id == MouseEvent.MOUSE_MOVED &&
            (mouseEvent.modifiersEx and AnyButtonDownMask == 0)
        if (isMotion) {
            if (hasWheelEvent) {
                val wheelElapsed = now - lastWheelEventNanos
                if (wheelElapsed < WheelQuietWindowNanos) {
                    pending = mouseEvent
                    pendingForce = true
                    val remainingMs = ((WheelQuietWindowNanos - wheelElapsed) / 1_000_000L).toInt().coerceAtLeast(1)
                    scheduleDeferredUpdate(remainingMs, now)
                    return
                }
            }
            if (lastLookupNanos != 0L) {
                val elapsed = now - lastLookupNanos
                if (elapsed < MotionThrottleNanos) {
                    pending = mouseEvent
                    val remainingMs = ((MotionThrottleNanos - elapsed) / 1_000_000L).toInt().coerceAtLeast(1)
                    scheduleDeferredUpdate(remainingMs, now)
                    return
                }
            }
        }

        deferredUpdateTimer.stop()
        scheduledFireNanos = 0L
        pending = mouseEvent
        if (!isMotion) pendingForce = true
        // Runs after Compose has handled the event, so its own cursor choice is already applied.
        if (!invokeLaterScheduled) {
            invokeLaterScheduled = true
            SwingUtilities.invokeLater {
                invokeLaterScheduled = false
                update(force = false)
            }
        }
    }

    private fun update(force: Boolean) {
        val effectiveForce = force || pendingForce
        pendingForce = false
        if (disposed) return
        val event = pending ?: return
        pending = null
        val source = event.component ?: return
        if (event.id == MouseEvent.MOUSE_EXITED || event.modifiersEx and AnyButtonDownMask != 0) {
            lastCheckedSource = null
            lastCheckedPoint = null
            release()
            return
        }
        val point = event.point
        if (!effectiveForce && source === lastCheckedSource) {
            val prev = lastCheckedPoint
            if (prev != null &&
                kotlin.math.abs(point.x - prev.x) < MinMoveDeltaPx &&
                kotlin.math.abs(point.y - prev.y) < MinMoveDeltaPx
            ) {
                return
            }
        }
        lastLookupNanos = System.nanoTime()
        lastCheckedSource = source
        lastCheckedPoint = Point(point)
        val wantsHand = isOverClickable(source, event)
        if (wantsHand) apply(source) else release()
    }

    private fun apply(component: Component) {
        if (overridden === component && component.cursor === handCursor) return
        // Leave cursors that a component chose on purpose (text caret, resize, autoscroll) alone.
        val current = component.cursor
        if (current !== handCursor && current.type != Cursor.DEFAULT_CURSOR) {
            overridden = null
            return
        }
        release()
        cursorBeforeOverride = if (component.isCursorSet) current else null
        overridden = component
        component.cursor = handCursor
    }

    fun dispose() {
        disposed = true
        deferredUpdateTimer.stop()
        scheduledFireNanos = 0L
        invokeLaterScheduled = false
        pendingForce = false
        hasWheelEvent = false
        pending = null
        lastCheckedSource = null
        lastCheckedPoint = null
        release()
    }

    fun release() {
        val component = overridden ?: return
        overridden = null
        // Only undo our own change; if Compose picked another cursor meanwhile, keep that one.
        if (component.cursor === handCursor) component.cursor = cursorBeforeOverride
        cursorBeforeOverride = null
    }

    @OptIn(ComposeToolingApi::class)
    private fun isOverClickable(source: Component, event: MouseEvent): Boolean {
        val pointInContent = SwingUtilities.convertPoint(source, event.point, window.contentPane)
        val scale = window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
        val position = Offset(pointInContent.x * scale, pointInContent.y * scale)
        // Later owners are layers drawn above earlier ones (popups, dialogs): the topmost one under the
        // pointer decides, so a dialog never lets clicks targets behind it show the hand.
        val owner = window.semanticsOwners.lastOrNull { owner ->
            owner.rootSemanticsNode.boundsInWindow.contains(position)
        } ?: return false
        val target = findClickable(owner.rootSemanticsNode, position) ?: return false
        val config = target.config
        return !config.contains(SemanticsProperties.Disabled) &&
            !config.contains(SemanticsProperties.EditableText) &&
            !config.contains(SemanticsActions.SetText)
    }

    /** The deepest clickable node under [position], preferring children drawn last (on top). */
    private fun findClickable(node: SemanticsNode, position: Offset): SemanticsNode? {
        val children = node.children
        for (index in children.indices.reversed()) {
            val child = children[index]
            if (child.boundsInWindow.contains(position)) {
                findClickable(child, position)?.let { return it }
            }
        }
        return node.takeIf { it.config.contains(SemanticsActions.OnClick) }
    }

    private companion object {
        const val AnyButtonDownMask =
            MouseEvent.BUTTON1_DOWN_MASK or MouseEvent.BUTTON2_DOWN_MASK or MouseEvent.BUTTON3_DOWN_MASK
        const val WheelIdleRefreshDelayMs = 120
        const val WheelQuietWindowNanos = 120_000_000L
        const val MotionThrottleNanos = 32_000_000L
        const val MinMoveDeltaPx = 2
    }
}
