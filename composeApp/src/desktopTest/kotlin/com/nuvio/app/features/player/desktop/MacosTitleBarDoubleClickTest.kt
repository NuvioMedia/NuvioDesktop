package com.nuvio.app.features.player.desktop

import androidx.compose.ui.geometry.Offset
import java.awt.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacosTitleBarDoubleClickTest {
    @Test
    fun `second press inside the title bar within timeout and slop is a double click`() {
        assertTrue(
            isTitleBarDoubleClick(
                previousPress = TitleBarPress(uptimeMs = 1_000L, position = Offset(500f, 12f)),
                press = TitleBarPress(uptimeMs = 1_250L, position = Offset(503f, 14f)),
                titleBarHeightPx = 28f,
                trafficLightsWidthPx = 80f,
                timeoutMs = 400L,
                slopPx = 4f,
            ),
        )
    }

    @Test
    fun `press below the title bar is not a double click`() {
        assertFalse(
            isTitleBarDoubleClick(
                previousPress = TitleBarPress(uptimeMs = 1_000L, position = Offset(500f, 12f)),
                press = TitleBarPress(uptimeMs = 1_250L, position = Offset(500f, 120f)),
                titleBarHeightPx = 28f,
                trafficLightsWidthPx = 80f,
                timeoutMs = 400L,
                slopPx = 4f,
            ),
        )
    }

    @Test
    fun `press over the traffic lights is not a double click`() {
        assertFalse(
            isTitleBarDoubleClick(
                previousPress = TitleBarPress(uptimeMs = 1_000L, position = Offset(30f, 12f)),
                press = TitleBarPress(uptimeMs = 1_250L, position = Offset(32f, 12f)),
                titleBarHeightPx = 28f,
                trafficLightsWidthPx = 80f,
                timeoutMs = 400L,
                slopPx = 4f,
            ),
        )
    }

    @Test
    fun `slow second press is not a double click`() {
        assertFalse(
            isTitleBarDoubleClick(
                previousPress = TitleBarPress(uptimeMs = 1_000L, position = Offset(500f, 12f)),
                press = TitleBarPress(uptimeMs = 1_500L, position = Offset(500f, 12f)),
                titleBarHeightPx = 28f,
                trafficLightsWidthPx = 80f,
                timeoutMs = 400L,
                slopPx = 4f,
            ),
        )
    }

    @Test
    fun `distant second press is not a double click`() {
        assertFalse(
            isTitleBarDoubleClick(
                previousPress = TitleBarPress(uptimeMs = 1_000L, position = Offset(400f, 12f)),
                press = TitleBarPress(uptimeMs = 1_250L, position = Offset(500f, 12f)),
                titleBarHeightPx = 28f,
                trafficLightsWidthPx = 80f,
                timeoutMs = 400L,
                slopPx = 4f,
            ),
        )
    }

    @Test
    fun `zoom toggles on and keeps unrelated extended state bits`() {
        val windowed = Frame.NORMAL or Frame.ICONIFIED
        val zoomed = toggledZoomExtendedState(windowed)

        assertEquals(Frame.MAXIMIZED_BOTH, zoomed and Frame.MAXIMIZED_BOTH)
        assertEquals(Frame.ICONIFIED, zoomed and Frame.ICONIFIED)
        assertEquals(windowed, toggledZoomExtendedState(zoomed))
    }

    @Test
    fun `double click action follows the macOS system preference`() {
        assertEquals(
            MacosTitleBarDoubleClickAction.Maximize,
            readMacosTitleBarDoubleClickAction { "Maximize" },
        )
        assertEquals(
            MacosTitleBarDoubleClickAction.Minimize,
            readMacosTitleBarDoubleClickAction { "Minimize\n" },
        )
        assertEquals(
            MacosTitleBarDoubleClickAction.None,
            readMacosTitleBarDoubleClickAction { "None" },
        )
        assertEquals(
            MacosTitleBarDoubleClickAction.Maximize,
            readMacosTitleBarDoubleClickAction { null },
        )
    }
}
