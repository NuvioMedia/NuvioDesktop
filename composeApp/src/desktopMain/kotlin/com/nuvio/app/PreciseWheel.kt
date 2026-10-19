package com.nuvio.app

import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.awt.Component
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.sqrt

// Compose can't be told to scroll "whatever is under the pointer", so scrolling
// the app drives itself goes back in as precise wheel events, which Compose on
// Linux applies as they come instead of animating them.

// The desktop's scale factor, which Chromium measures DIP against. Java on X11
// only scales by whole numbers, so under fractional scaling AWT works in device
// pixels and only sun.java2d.uiScale, which Skiko sets from the desktop, holds
// the real factor.
internal val Component.displayScale: Double
    get() = System.getProperty("sun.java2d.uiScale")?.toDoubleOrNull() ?: awtScale

// Device pixels per AWT coordinate.
internal val Component.awtScale: Double
    get() = graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0

// Posts the pixels as one wheel event at the pointer (screen coordinates) and
// returns what is left to scroll. Compose ignores deltas under half a pixel, so
// smaller amounts are held back until they add up.
internal fun postPreciseWheel(
    component: Component,
    pointer: Point,
    pixels: Double,
    horizontal: Boolean,
    modifiers: Int,
): Double {
    if (abs(pixels) < 1) return pixels
    // Compose scales a wheel rotation by the size of the scrolled viewport
    // (DesktopScrollable.desktop.kt). That viewport isn't known here, so assume
    // it fills the window, as the app's scrolling screens roughly do.
    val extent = ((if (horizontal) component.width else component.height) * component.awtScale).coerceAtLeast(1.0)
    val pixelsPerRotation = if (DesktopHostOs.current == DesktopHostOs.WINDOWS) extent / 20 else sqrt(extent)
    // Post where the pointer is and with the buttons it holds. Anything else
    // disagrees with the real mouse events, and Compose fills that in with
    // made-up moves, presses and releases.
    val local = Point(pointer).also { SwingUtilities.convertPointFromScreen(it, component) }
    // Compose reads a shifted wheel as horizontal.
    val shift = if (horizontal) InputEvent.SHIFT_DOWN_MASK else 0
    Toolkit.getDefaultToolkit().systemEventQueue.postEvent(
        MouseWheelEvent(
            component,
            MouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            (modifiers and InputEvent.SHIFT_DOWN_MASK.inv()) or shift,
            local.x,
            local.y,
            pointer.x,
            pointer.y,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            1,
            // No whole rotation is what makes it precise.
            0,
            pixels / pixelsPerRotation,
        ),
    )
    return 0.0
}
