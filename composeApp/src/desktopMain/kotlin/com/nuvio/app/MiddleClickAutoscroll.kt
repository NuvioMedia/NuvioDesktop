package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.awt.Component
import java.awt.Cursor
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

// Middle-click autoscroll as Chromium does it (blink's AutoscrollController):
// every axis further than 15 units from the press point scrolls at
// 0.04 * distance^2.2 DIP/s and the cursor points the way it scrolls. Releasing
// after moving stops it; a plain click keeps it going until the next click or
// key press.
//
// Blink takes the distance in screen DIP and divides it by devicePixelRatio once
// more, so a unit is a DIP times the display scale. That makes it slower on
// scaled displays, which is what Chromium users are used to.

private const val DEAD_ZONE = 15.0

// Blink's velocity multiplier (0.000008) times its FixedVelocityCurve's 5000.
private const val SPEED_FACTOR = 0.04
private const val SPEED_EXPONENT = 2.2

// Chromium uses the themed all-scroll and resize cursors on Linux, which is
// what these resolve to under X11. Indexed by (sign(y) + 1) * 3 + sign(x) + 1.
private val PanningIcons = intArrayOf(
    Cursor.NW_RESIZE_CURSOR, Cursor.N_RESIZE_CURSOR, Cursor.NE_RESIZE_CURSOR,
    Cursor.W_RESIZE_CURSOR, Cursor.MOVE_CURSOR, Cursor.E_RESIZE_CURSOR,
    Cursor.SW_RESIZE_CURSOR, Cursor.S_RESIZE_CURSOR, Cursor.SE_RESIZE_CURSOR,
).map { PointerIcon(Cursor.getPredefinedCursor(it)) }

private class Autoscroll(val component: Component, val origin: Point) {
    var moved = false

    // Released without moving: keeps scrolling until the next click.
    var toggled = false
    var icon by mutableStateOf(PanningIcons[4])
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MiddleClickAutoscroll(content: @Composable () -> Unit) {
    if (DesktopHostOs.current != DesktopHostOs.LINUX && DesktopHostOs.current != DesktopHostOs.WINDOWS) {
        content()
        return
    }
    var active by remember { mutableStateOf<Autoscroll?>(null) }
    val windowInfo = LocalWindowInfo.current

    Box(
        propagateMinConstraints = true,
        modifier = Modifier
            .fillMaxSize()
            .pointerHoverIcon(active?.icon ?: PointerIcon.Default, overrideDescendants = active != null)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val current = active
                        val isMiddle = event.button == PointerButton.Tertiary
                        val consume = when (event.type) {
                            // Any press ends a running autoscroll without clicking through.
                            PointerEventType.Press -> {
                                active = if (current == null && isMiddle) startAutoscroll(event) else null
                                current != null || active != null
                            }
                            PointerEventType.Release -> {
                                if (current != null && isMiddle) {
                                    if (current.moved) active = null else current.toggled = true
                                }
                                current != null && isMiddle
                            }
                            else -> false
                        }
                        if (consume) event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        content()
    }

    active?.let { scroll ->
        DisposableEffect(scroll) {
            // Like Chromium, any key stops it and keys are swallowed meanwhile.
            // This sits in front of Compose, which only sees keys with focus.
            val stopOnKey = KeyEventDispatcher { event ->
                if (event.id == KeyEvent.KEY_PRESSED) active = null
                true
            }
            val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            focusManager.addKeyEventDispatcher(stopOnKey)
            onDispose { focusManager.removeKeyEventDispatcher(stopOnKey) }
        }
        LaunchedEffect(scroll) {
            var pendingX = 0.0
            var pendingY = 0.0
            var lastFrame = withFrameNanos { it }
            while (windowInfo.isWindowFocused) {
                val frame = withFrameNanos { it }
                val elapsedSeconds = (frame - lastFrame) / 1e9
                lastFrame = frame
                val pointer = MouseInfo.getPointerInfo()?.location ?: continue
                val component = scroll.component
                val unitsPerAwt = component.awtScale / (component.displayScale * component.displayScale)
                val velocityX = velocity((pointer.x - scroll.origin.x) * unitsPerAwt)
                val velocityY = velocity((pointer.y - scroll.origin.y) * unitsPerAwt)
                if (velocityX != 0.0 || velocityY != 0.0) scroll.moved = true
                scroll.icon = PanningIcons[(velocityY.sign.toInt() + 1) * 3 + velocityX.sign.toInt() + 1]
                // The middle button stays held unless it was clicked to toggle.
                val buttons = if (scroll.toggled) 0 else InputEvent.BUTTON2_DOWN_MASK
                pendingX += velocityX * component.displayScale * elapsedSeconds
                pendingY += velocityY * component.displayScale * elapsedSeconds
                pendingX = postPreciseWheel(component, pointer, pendingX, horizontal = true, buttons)
                pendingY = postPreciseWheel(component, pointer, pendingY, horizontal = false, buttons)
            }
            active = null
        }
    }
}

private fun startAutoscroll(event: PointerEvent): Autoscroll? {
    val component = event.awtEventOrNull?.component ?: return null
    val origin = MouseInfo.getPointerInfo()?.location ?: return null
    return Autoscroll(component, origin)
}

// Signed DIP/s along one axis.
private fun velocity(distance: Double): Double =
    if (abs(distance) <= DEAD_ZONE) 0.0
    else distance.sign * SPEED_FACTOR * abs(distance).pow(SPEED_EXPONENT)
