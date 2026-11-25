package com.nuvio.app.controller

import com.nuvio.app.controller.DesktopControllerInput.AnalogScrollState
import com.nuvio.app.controller.DesktopControllerInput.ForegroundGate
import com.nuvio.app.controller.DesktopControllerInput.PadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControllerForegroundGateTest {
    private val events = mutableListOf<ControllerEvent>()
    private val pad = PadState(dispatch = events::add)
    private val analog = AnalogScrollState(dispatch = events::add)
    private val gate = ForegroundGate()

    private fun poll(
        foreground: Boolean,
        directions: Set<ControllerDirection> = emptySet(),
        buttons: Set<XboxButton> = emptySet(),
        stickY: Float = 0f,
        // A wall-clock time, as the poller passes; right after 0 the stick reports a stop.
        now: Long = 1_000_000L,
    ) {
        if (gate.allow(foreground, pad, analog, directions, buttons)) {
            pad.update(directions, buttons, now)
            analog.update(0f, stickY, now)
        }
    }

    @Test
    fun inputReachesTheAppWhileItIsInFront() {
        poll(foreground = true, buttons = setOf(XboxButton.A))
        poll(foreground = true, directions = setOf(ControllerDirection.Up))

        assertEquals(
            listOf(
                ControllerEvent.ButtonPressed(XboxButton.A),
                ControllerEvent.Navigate(ControllerDirection.Up, isRepeat = false),
                ControllerEvent.ButtonReleased(XboxButton.A),
            ),
            events,
        )
    }

    @Test
    fun inputIsDroppedWhileAnotherAppIsInFront() {
        poll(foreground = false, buttons = setOf(XboxButton.A))
        poll(foreground = false, buttons = setOf(XboxButton.B), directions = setOf(ControllerDirection.Up))
        poll(foreground = false, stickY = 0.8f)

        assertTrue(events.isEmpty(), "unexpected events: $events")
    }

    @Test
    fun heldButtonsAreReleasedWhenTheAppLosesFocus() {
        poll(foreground = true, buttons = setOf(XboxButton.RT))
        events.clear()

        poll(foreground = false, buttons = setOf(XboxButton.RT))

        assertEquals(listOf<ControllerEvent>(ControllerEvent.ButtonReleased(XboxButton.RT)), events)
    }

    @Test
    fun aScrollInProgressStopsWhenTheAppLosesFocus() {
        poll(foreground = true, stickY = 0.8f, now = 1_000L)
        events.clear()

        poll(foreground = false, stickY = 0.8f, now = 1_010L)

        assertEquals(listOf<ControllerEvent>(ControllerEvent.AnalogScroll(0f, 0f)), events)
    }

    @Test
    fun inputHeldWhenComingBackIsIgnoredUntilReleased() {
        poll(foreground = false, buttons = setOf(XboxButton.A), directions = setOf(ControllerDirection.Down))

        poll(foreground = true, buttons = setOf(XboxButton.A), directions = setOf(ControllerDirection.Down), now = 0L)
        poll(foreground = true, buttons = setOf(XboxButton.A), directions = setOf(ControllerDirection.Down), now = 2_000L)
        assertTrue(events.isEmpty(), "held input fired after coming back: $events")

        // Letting go does not report a release for a press the app never saw.
        poll(foreground = true, now = 2_100L)
        assertTrue(events.isEmpty(), "release reported for an unseen press: $events")

        // A fresh press works normally.
        poll(foreground = true, buttons = setOf(XboxButton.A), directions = setOf(ControllerDirection.Down), now = 2_200L)
        assertEquals(
            listOf(
                ControllerEvent.Navigate(ControllerDirection.Down, isRepeat = false),
                ControllerEvent.ButtonPressed(XboxButton.A),
            ),
            events,
        )
    }

    @Test
    fun theStickIsIgnoredAfterComingBackUntilItReturnsToRest() {
        poll(foreground = false, stickY = 0.8f)

        poll(foreground = true, stickY = 0.8f, now = 0L)
        assertTrue(events.isEmpty(), "stick held from the other app scrolled: $events")

        poll(foreground = true, stickY = 0f, now = 10L)
        poll(foreground = true, stickY = 0.6f, now = 20L)
        assertEquals(listOf<ControllerEvent>(ControllerEvent.AnalogScroll(0f, 0.6f)), events)
    }

    @Test
    fun onlyTheFocusChangeIsHandledOnce() {
        poll(foreground = true, buttons = setOf(XboxButton.A))
        events.clear()

        repeat(3) { poll(foreground = false, buttons = setOf(XboxButton.A)) }

        assertEquals(listOf<ControllerEvent>(ControllerEvent.ButtonReleased(XboxButton.A)), events)
        assertFalse(gate.allow(false, pad, analog, emptySet(), emptySet()))
    }
}
