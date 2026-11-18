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
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

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
            controller.release()
        }
    }
}

private const val HandCursorEventMask =
    AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK

private class HandCursorController(private val window: ComposeWindow) : AWTEventListener {
    private val handCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    private var overridden: Component? = null
    private var cursorBeforeOverride: Cursor? = null
    private var pending: MouseEvent? = null

    override fun eventDispatched(event: AWTEvent) {
        val mouseEvent = event as? MouseEvent ?: return
        val source = mouseEvent.component ?: return
        if (source !== window && !SwingUtilities.isDescendingFrom(source, window)) return
        val alreadyScheduled = pending != null
        pending = mouseEvent
        // Runs after Compose has handled the event, so its own cursor choice is already applied.
        if (!alreadyScheduled) SwingUtilities.invokeLater(::update)
    }

    private fun update() {
        val event = pending ?: return
        pending = null
        val source = event.component ?: return
        val wantsHand = event.id != MouseEvent.MOUSE_EXITED &&
            event.modifiersEx and AnyButtonDownMask == 0 &&
            isOverClickable(source, event)
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
    }
}
