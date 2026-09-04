package com.nuvio.app

import java.awt.AWTEvent
import java.awt.Component
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

// X11 numbers the buttons above the wheel differently from the other desktop
// toolkits and Java's XToolkit passes that numbering straight through. The
// vertical wheel becomes a MouseWheelEvent, but the horizontal wheel stays a
// pair of button presses -- X11 buttons 6 and 7, reported as AWT buttons 4 and
// 5 -- so the thumb buttons (X11 8 and 9) arrive as AWT buttons 6 and 7.
//
// Compose derives its PointerButton from the AWT button number minus one, which
// leaves two separate defects on Linux:
//
//   * the thumb back button arrives as button 6, never matches
//     PointerButton.Back, and back navigation does nothing;
//   * a horizontal wheel tick arrives as button 4 or 5, which is exactly what
//     Compose reads as Back and Forward, so scrolling a row sideways navigates
//     the app backwards instead of moving the row.
//
// A horizontal scroll only reaches Compose as a MouseWheelEvent with shift held
// -- ComposeSceneMediator builds Offset(rotation, 0) when isShiftDown and
// Offset(0, rotation) otherwise -- so the wheel buttons have to be translated
// into that shape rather than merely renumbered.
//
// Both fixes have to replace the original event instead of adding to it, which
// an AWTEventListener cannot do: it observes and cannot consume. Pushing an
// EventQueue hands us each event before dispatch, so the button press is swapped
// for the event Compose understands and the original never reaches the app.
// Shared code stays untouched, and Windows and macOS never see this -- their
// toolkits already number these buttons the way Compose reads them.
//
// Install this only once the window peer exists. Reaching for the AWT toolkit
// during main() forces it up before GTK has finished initializing, which is the
// ordering main() opens by warning about, and it leaves the window unable to
// close.

// AWT numbers, i.e. what MouseEvent.getButton() reports on X11.
private const val AWT_WHEEL_LEFT = 4
private const val AWT_WHEEL_RIGHT = 5
private const val AWT_THUMB_BACK = 6

// The AWT button Compose reads as PointerButton.Back.
private const val COMPOSE_BACK = 4

// One notch per press, matching what the toolkit sends for the vertical wheel.
private const val WHEEL_SCROLL_AMOUNT = 3

@Volatile
private var extendedMouseButtonsInstalled = false

fun installLinuxExtendedMouseButtons() {
    if (!System.getProperty("os.name", "").lowercase().contains("linux")) return
    synchronized(LinuxExtendedMouseButtonsLock) {
        if (extendedMouseButtonsInstalled) return
        extendedMouseButtonsInstalled = true
    }
    // Never let an input convenience take the app down with it.
    runCatching {
        Toolkit.getDefaultToolkit().systemEventQueue.push(LinuxExtendedMouseButtonQueue())
    }
}

private class LinuxExtendedMouseButtonQueue : EventQueue() {
    override fun dispatchEvent(event: AWTEvent) {
        // A translation fault must not cost the app an event, so fall back to
        // dispatching what the toolkit gave us.
        val translated = runCatching { translate(event) }.getOrDefault(event)
        if (translated != null) {
            super.dispatchEvent(translated)
        }
    }

    // Returns the event to dispatch, or null to drop it.
    private fun translate(event: AWTEvent): AWTEvent? {
        // Wheel events are already the shape Compose wants, and only pressed,
        // released and clicked carry a button number.
        if (event !is MouseEvent || event is MouseWheelEvent) return event
        if (event.id != MouseEvent.MOUSE_PRESSED &&
            event.id != MouseEvent.MOUSE_RELEASED &&
            event.id != MouseEvent.MOUSE_CLICKED
        ) {
            return event
        }
        val component = event.component ?: return event
        return when (event.button) {
            AWT_THUMB_BACK -> withButton(event, component, COMPOSE_BACK)
            AWT_WHEEL_LEFT -> horizontalScroll(event, component, rotation = -1)
            AWT_WHEEL_RIGHT -> horizontalScroll(event, component, rotation = 1)
            else -> event
        }
    }

    private fun withButton(source: MouseEvent, component: Component, button: Int): MouseEvent =
        MouseEvent(
            component,
            source.id,
            source.getWhen(),
            source.modifiersEx,
            source.x,
            source.y,
            source.xOnScreen,
            source.yOnScreen,
            source.clickCount,
            source.isPopupTrigger,
            button,
        )

    private fun horizontalScroll(source: MouseEvent, component: Component, rotation: Int): AWTEvent? {
        // The press is the tick. The release and click that follow it would
        // scroll the row a second and third time, and passing them through
        // instead would navigate back, which is the defect being fixed.
        if (source.id != MouseEvent.MOUSE_PRESSED) return null
        return MouseWheelEvent(
            component,
            MouseEvent.MOUSE_WHEEL,
            source.getWhen(),
            source.modifiersEx or InputEvent.SHIFT_DOWN_MASK,
            source.x,
            source.y,
            source.xOnScreen,
            source.yOnScreen,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            WHEEL_SCROLL_AMOUNT,
            rotation,
            rotation.toDouble(),
        )
    }
}

private object LinuxExtendedMouseButtonsLock
