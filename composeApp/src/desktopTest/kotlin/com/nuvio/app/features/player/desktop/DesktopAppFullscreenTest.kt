package com.nuvio.app.features.player.desktop

import androidx.compose.ui.window.WindowPlacement
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAppFullscreenTest {
    @Test
    fun `fullscreen shortcuts continue to toggle`() {
        assertTrue(
            shouldHandleDesktopFullscreenKey(
                eventId = KeyEvent.KEY_PRESSED,
                keyCode = KeyEvent.VK_F11,
                modifiersEx = 0,
            ),
        )
        assertTrue(
            shouldHandleDesktopFullscreenKey(
                eventId = KeyEvent.KEY_PRESSED,
                keyCode = KeyEvent.VK_F,
                modifiersEx = InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK,
            ),
        )
    }

    @Test
    fun `escape exits fullscreen only when enabled and unmodified`() {
        assertTrue(
            shouldHandleDesktopFullscreenKey(
                eventId = KeyEvent.KEY_PRESSED,
                keyCode = KeyEvent.VK_ESCAPE,
                modifiersEx = 0,
                isFullscreen = true,
                escapeExitsFullscreen = true,
            ),
        )
        assertFalse(
            shouldHandleDesktopFullscreenKey(
                eventId = KeyEvent.KEY_PRESSED,
                keyCode = KeyEvent.VK_ESCAPE,
                modifiersEx = 0,
                isFullscreen = true,
                escapeExitsFullscreen = false,
            ),
        )
        assertFalse(
            shouldHandleDesktopFullscreenKey(
                eventId = KeyEvent.KEY_PRESSED,
                keyCode = KeyEvent.VK_ESCAPE,
                modifiersEx = InputEvent.SHIFT_DOWN_MASK,
                isFullscreen = true,
                escapeExitsFullscreen = true,
            ),
        )
    }

    @Test
    fun `escape does not consume windowed navigation`() {
        assertFalse(
            shouldHandleDesktopFullscreenKey(
                eventId = KeyEvent.KEY_PRESSED,
                keyCode = KeyEvent.VK_ESCAPE,
                modifiersEx = 0,
                isFullscreen = false,
                escapeExitsFullscreen = true,
            ),
        )
    }

    @Test
    fun `key releases and unrelated keys are ignored`() {
        assertFalse(
            shouldHandleDesktopFullscreenKey(
                eventId = KeyEvent.KEY_RELEASED,
                keyCode = KeyEvent.VK_ESCAPE,
                modifiersEx = 0,
            ),
        )
        assertFalse(
            shouldHandleDesktopFullscreenKey(
                eventId = KeyEvent.KEY_PRESSED,
                keyCode = KeyEvent.VK_ENTER,
                modifiersEx = 0,
            ),
        )
        assertFalse(
            shouldHandleDesktopFullscreenKey(
                eventId = KeyEvent.KEY_PRESSED,
                keyCode = KeyEvent.VK_ESCAPE,
                modifiersEx = 0,
            ),
        )
    }

    @Test
    fun `native fullscreen exit lets the window listener restore placement`() {
        val updates = mutableListOf<String>()

        applyMacosComposeFullscreenExit(
            restorePlacement = WindowPlacement.Maximized,
            requestNativeFullscreenExit = {
                updates += "native"
                true
            },
            clearComposeFullscreen = { updates += "compose" },
            setStatePlacement = { updates += "state:$it" },
        )

        assertEquals(listOf("native"), updates)
    }

    @Test
    fun `compose fallback clears fullscreen before restoring maximized placement`() {
        val updates = mutableListOf<Pair<String, WindowPlacement>>()

        applyMacosComposeFullscreenExit(
            restorePlacement = WindowPlacement.Maximized,
            requestNativeFullscreenExit = { false },
            clearComposeFullscreen = { updates += "compose" to WindowPlacement.Floating },
            setStatePlacement = { updates += "state" to it },
        )

        assertEquals(
            listOf(
                "compose" to WindowPlacement.Floating,
                "state" to WindowPlacement.Maximized,
            ),
            updates,
        )
    }

    @Test
    fun `fullscreen cannot be restored as its own exit placement`() {
        val updates = mutableListOf<Pair<String, WindowPlacement>>()

        applyMacosComposeFullscreenExit(
            restorePlacement = WindowPlacement.Fullscreen,
            requestNativeFullscreenExit = { false },
            clearComposeFullscreen = { updates += "compose" to WindowPlacement.Floating },
            setStatePlacement = { updates += "state" to it },
        )

        assertEquals(
            listOf(
                "compose" to WindowPlacement.Floating,
                "state" to WindowPlacement.Floating,
            ),
            updates,
        )
    }
}
