package com.nuvio.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import com.nuvio.app.core.ui.PlatformTouchScrollState
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlin.math.abs

private var windowsTouchQueue: WindowsTouchEventQueue? = null

internal fun installWindowsTouchScroll() {
    if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return
    if (windowsTouchQueue != null) return
    runCatching {
        val queue = WindowsTouchEventQueue()
        Toolkit.getDefaultToolkit().systemEventQueue.push(queue)
        windowsTouchQueue = queue
    }
}

// A posted wheel can otherwise end up behind an already-queued touch release,
// with stale button state that makes Compose synthesize another press. Dispatch
// after the current event has finished (avoiding reentrant Compose input), but
// before the next native event changes the pointer's position or button state.
internal class WindowsTouchEventQueue : EventQueue() {
    private val pending = ArrayDeque<MouseWheelEvent>()

    fun afterCurrentEvent(event: MouseWheelEvent) {
        check(isDispatchThread())
        pending.addLast(event)
    }

    public override fun dispatchEvent(event: AWTEvent) {
        try {
            super.dispatchEvent(event)
        } finally {
            while (pending.isNotEmpty()) super.dispatchEvent(pending.removeFirst())
        }
    }
}

// AWT identifies the primary touchscreen press, but Compose Desktop converts it
// to PointerType.Mouse. Scrollable ignores mouse drags, unlike sliders and other
// draggable controls. Translate only otherwise-unhandled touch drags to wheel
// input so existing scroll containers (including nested rails) can handle them.
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.windowsTouchScroll(): Modifier {
    if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return this
    return touchScrollFallback(
        isTouchPress = { event ->
            val native = event.awtEventOrNull
            if (native?.id == MouseEvent.MOUSE_PRESSED) PlatformTouchScrollState.isDragging = false
            windowsTouchQueue != null && native?.let(WindowsTouchMouseEvents::isTouchPress) == true
        },
        suppressPress = { event ->
            PlatformTouchScrollState.isDragging &&
                event.awtEventOrNull?.id != MouseEvent.MOUSE_PRESSED
        },
        onGestureEnd = { event ->
            // Disposing a popup also produces a synthetic release. Ownership
            // ends on the physical AWT release, not that cancellation event.
            if (event.awtEventOrNull?.id == MouseEvent.MOUSE_RELEASED) {
                PlatformTouchScrollState.isDragging = false
            }
        },
        scroll = ::postTouchScroll,
    )
}

internal object WindowsTouchMouseEvents {
    // There is no public AWT accessor. The launch configuration opens this
    // package; gracefully keep normal mouse input if a different JDK lacks it.
    private val touchField = runCatching {
        MouseEvent::class.java.getDeclaredField("causedByTouchEvent").apply { isAccessible = true }
    }.getOrNull()

    fun isTouchPress(event: MouseEvent): Boolean =
        event.id == MouseEvent.MOUSE_PRESSED && event.button == MouseEvent.BUTTON1 &&
            runCatching { touchField?.getBoolean(event) == true }.getOrDefault(false)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun postTouchScroll(event: PointerEvent, pixels: Offset) {
    val native = event.awtEventOrNull ?: return
    val queue = windowsTouchQueue ?: return
    PlatformTouchScrollState.isDragging = true
    postPreciseWheel(
        component = native.component,
        pointer = native.locationOnScreen,
        pixels = if (pixels.x != 0f) pixels.x.toDouble() else pixels.y.toDouble(),
        horizontal = pixels.x != 0f,
        modifiers = native.modifiersEx,
        postEvent = queue::afterCurrentEvent,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.touchScrollFallback(
    isTouchPress: (PointerEvent) -> Boolean,
    suppressPress: (PointerEvent) -> Boolean = { false },
    onGestureEnd: (PointerEvent) -> Unit = {},
    scroll: (PointerEvent, Offset) -> Unit,
): Modifier = pointerInput(isTouchPress, suppressPress, onGestureEnd, scroll) {
    awaitEachGesture {
        // Observe the press before descendants consume it for their click.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.type != PointerType.Mouse) return@awaitEachGesture
        try {
            if (suppressPress(currentEvent)) {
                // Removing a popup while the button is held can make Compose
                // synthesize a down in the exposed root from a native drag.
                // Consume that whole remainder, including the release.
                var rejected = currentEvent
                while (true) {
                    rejected.changes.forEach { it.consume() }
                    if (rejected.changes.none { it.pressed }) break
                    rejected = awaitPointerEvent(PointerEventPass.Initial)
                }
                return@awaitEachGesture
            }
            if (!isTouchPress(currentEvent)) return@awaitEachGesture
            val origin = down.position
            var horizontal: Boolean? = null
            var pending = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                // The press may be consumed for a click, and the synthesized wheel
                // events must reach scrollables without ending this drag.
                if (event.type != PointerEventType.Move && event.type != PointerEventType.Release) continue
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || event.changes.count { it.pressed } != 1) break
                // Give native draggables (seek/volume sliders, scrollbars, text
                // selection, reordering) ownership before starting the fallback.
                if (change.isConsumed) break
                val movement = change.position - origin
                val axis = horizontal
                val delta = if (axis == null) {
                    if (maxOf(abs(movement.x), abs(movement.y)) <= viewConfiguration.touchSlop) continue
                    horizontal = abs(movement.x) > abs(movement.y)
                    val distance = if (horizontal == true) movement.x else movement.y
                    // Like a regular drag, spend the initial movement on touch slop.
                    distance - kotlin.math.sign(distance) * viewConfiguration.touchSlop
                } else {
                    val step = change.position - change.previousPosition
                    if (axis) step.x else step.y
                }
                // Consume before the Final pass to cancel a pending card/button
                // click. Keep consuming until release even if this step is tiny.
                change.consume()
                pending -= delta
                if (abs(pending) >= 1f) {
                    scroll(event, if (horizontal == true) Offset(pending, 0f) else Offset(0f, pending))
                    pending = 0f
                }
            }
        } finally {
            // Cancellation while pressed means this root may have disappeared.
            // Keep ownership until another root sees the release or a new press.
            if (currentEvent.changes.none { it.pressed }) onGestureEnd(currentEvent)
        }
    }
}
