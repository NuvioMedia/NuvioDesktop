package com.nuvio.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import java.awt.Canvas
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.InvocationEvent
import javax.swing.SwingUtilities
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsTouchScrollTest {
    @get:Rule
    val compose = createComposeRule()

    private val scrolls = mutableListOf<Offset>()
    private var clicks = 0

    @Test
    fun synthesizedWheelRunsAfterCurrentDispatchAndBeforeRelease() {
        val order = mutableListOf<String>()
        val queue = WindowsTouchEventQueue()
        val component = Canvas().apply {
            addMouseWheelListener { order += "wheel" }
        }
        SwingUtilities.invokeAndWait {
            queue.dispatchEvent(InvocationEvent(component, Runnable {
                order += "drag start"
                queue.afterCurrentEvent(MouseWheelEvent(
                    component, MouseEvent.MOUSE_WHEEL, 0L, 0, 10, 10,
                    10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 0, 0.5,
                ))
                order += "drag end"
            }))
            queue.dispatchEvent(InvocationEvent(component, Runnable { order += "release" }))
        }
        assertEquals(listOf("drag start", "drag end", "wheel", "release"), order)
    }

    @Test
    fun awtTouchMarkerDistinguishesTouchFromMouseAndSecondaryButtons() {
        val field = MouseEvent::class.java.getDeclaredField("causedByTouchEvent").apply { isAccessible = true }
        fun event(id: Int, button: Int, touch: Boolean) = MouseEvent(
            Canvas(), id, 0L, 0, 10, 10, 10, 10, 1, false, button,
        ).also { field.setBoolean(it, touch) }

        assertTrue(WindowsTouchMouseEvents.isTouchPress(event(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, true)))
        assertFalse(WindowsTouchMouseEvents.isTouchPress(event(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, false)))
        assertFalse(WindowsTouchMouseEvents.isTouchPress(event(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON3, true)))
        assertFalse(WindowsTouchMouseEvents.isTouchPress(event(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1, true)))
    }

    @Test
    fun touchTapAndSmallJitterStillClick() {
        showContent(touch = true)
        compose.onNodeWithTag("target").performMouseInput {
            moveTo(Offset(100f, 100f))
            press()
            moveTo(Offset(101f, 101f))
            release()
        }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertTrue(scrolls.isEmpty())
        }
    }

    @Test
    fun touchDragScrollsVerticallyAcrossSeveralMovesWithoutClicking() {
        showContent(touch = true)
        compose.onNodeWithTag("target").performMouseInput {
            moveTo(Offset(100f, 200f))
            press()
            moveTo(Offset(100f, 130f))
            moveTo(Offset(110f, 100f))
            moveTo(Offset(100f, 70f))
            release()
        }
        compose.runOnIdle {
            assertEquals(3, scrolls.size)
            assertTrue(scrolls.all { it.x == 0f && it.y > 0f })
            assertEquals(0, clicks)
        }
    }

    @Test
    fun horizontalTouchDragLocksDirectionAndCanReverse() {
        showContent(touch = true)
        compose.onNodeWithTag("target").performMouseInput {
            moveTo(Offset(220f, 100f))
            press()
            moveTo(Offset(150f, 101f))
            moveTo(Offset(120f, 130f))
            moveTo(Offset(180f, 180f))
            release()
        }
        compose.runOnIdle {
            assertEquals(3, scrolls.size)
            assertTrue(scrolls.all { it.y == 0f })
            assertTrue(scrolls.first().x > 0f)
            assertTrue(scrolls.last().x < 0f)
            assertEquals(0, clicks)
        }
    }

    @Test
    fun realMouseDragDoesNotBecomeScrolling() {
        showContent(touch = false)
        compose.onNodeWithTag("target").performMouseInput {
            moveTo(Offset(100f, 220f))
            press()
            moveTo(Offset(100f, 60f))
            release()
        }
        compose.runOnIdle { assertTrue(scrolls.isEmpty()) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun wheelEventsHandledByChildDoNotInterruptTouchDrag() {
        showContent(
            touch = true,
            child = Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
                event.changes.forEach { it.consume() }
            },
        )
        compose.onNodeWithTag("target").performMouseInput {
            moveTo(Offset(100f, 220f))
            press()
            moveTo(Offset(100f, 150f))
            scroll(1f)
            moveTo(Offset(100f, 80f))
            release()
        }
        compose.runOnIdle { assertEquals(2, scrolls.size) }
    }

    @Test
    fun childDraggableRetainsTouchGesture() {
        var childDrags = 0
        showContent(
            touch = true,
            child = Modifier.pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    childDrags++
                }
            },
        )
        compose.onNodeWithTag("target").performMouseInput {
            moveTo(Offset(220f, 100f))
            press()
            moveTo(Offset(150f, 100f))
            moveTo(Offset(80f, 100f))
            release()
        }
        compose.runOnIdle {
            assertTrue(childDrags > 0)
            assertTrue(scrolls.isEmpty())
        }
    }

    @Test
    fun genuineComposeTouchStillUsesNativeScrolling() {
        var position = { 0 }
        compose.setContent {
            val state = rememberScrollState()
            position = { state.value }
            Box(Modifier.size(300.dp).touchScrollFallback({ true }) { _, delta -> scrolls += delta }) {
                Box(Modifier.size(300.dp).verticalScroll(state).testTag("target")) {
                    Box(Modifier.size(300.dp, 1200.dp))
                }
            }
        }
        compose.onNodeWithTag("target").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue(position() > 0)
            assertTrue(scrolls.isEmpty())
        }
    }

    @Test
    fun nextTapAfterScrollingWorks() {
        showContent(touch = true)
        compose.onNodeWithTag("target").performMouseInput {
            moveTo(Offset(100f, 220f))
            press()
            moveTo(Offset(100f, 60f))
            release()
            click()
        }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun nestedFallbacksOnlyScrollTheInnerScope() {
        var outerScrolls = 0
        compose.setContent {
            Box(Modifier.size(300.dp).touchScrollFallback({ true }) { _, _ -> outerScrolls++ }) {
                Box(Modifier.size(300.dp).touchScrollFallback({ true }) { _, delta -> scrolls += delta }) {
                    Box(Modifier.size(300.dp).clickable { clicks++ }.testTag("target"))
                }
            }
        }
        compose.onNodeWithTag("target").performMouseInput {
            moveTo(Offset(100f, 220f))
            press()
            moveTo(Offset(100f, 150f))
            moveTo(Offset(100f, 80f))
            release()
        }
        compose.runOnIdle {
            assertEquals(2, scrolls.size)
            assertEquals(0, outerScrolls)
            assertEquals(0, clicks)
        }
    }

    private fun showContent(touch: Boolean, child: Modifier = Modifier) {
        compose.setContent {
            Box(Modifier.size(300.dp).touchScrollFallback({ touch }) { _, delta -> scrolls += delta }) {
                Box(Modifier.size(300.dp).then(child).clickable { clicks++ }.testTag("target"))
            }
        }
    }
}
