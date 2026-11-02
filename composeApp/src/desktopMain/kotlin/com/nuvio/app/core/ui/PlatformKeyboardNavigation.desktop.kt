package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

@Composable
actual fun PlatformKeyboardNavigationHost() {
    val focusManager = LocalFocusManager.current

    DesktopKeyboardShortcutHost()

    DisposableEffect(Unit) {
        DesktopGamepadNavigation.start()
        onDispose { DesktopGamepadNavigation.stop() }
    }

    DisposableEffect(Unit) {
        val dispatcher = KeyEventDispatcher { event: KeyEvent ->
            if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            val direction = when (event.keyCode) {
                KeyEvent.VK_LEFT -> FocusDirection.Left
                KeyEvent.VK_RIGHT -> FocusDirection.Right
                KeyEvent.VK_UP -> FocusDirection.Up
                KeyEvent.VK_DOWN -> FocusDirection.Down
                else -> return@KeyEventDispatcher false
            }
            // Chrome regions (sidebar, top bar) sit beside the content but never overlap it, so
            // Compose's geometric focus search can land on nothing or on the wrong node. Leaving
            // them is therefore explicit: Right from the sidebar and Down from the top bar always
            // hand focus to the content entry, which forwards it to the first item of the screen.
            val leftChrome = when (direction) {
                FocusDirection.Right -> NavRailFocus.hasFocus
                FocusDirection.Down -> TopBarFocus.hasFocus
                else -> false
            }
            if (leftChrome && ContentEntry.request()) return@KeyEventDispatcher true

            // Everywhere else a failed directional move keeps the current item instead of falling
            // back to "next", which used to wrap rows into their neighbours (Down on the top bar
            // moved right, the last item of a row jumped to the next shelf, Left/Right in the
            // sidebar walked down the column). Left at the content edge is the one escape hatch:
            // it returns to the navigation rail. Anything else only seeds focus when nothing is
            // focused at all.
            if (!focusManager.moveFocus(direction)) {
                if (direction == FocusDirection.Left && !NavRailFocus.hasFocus && RailEntry.request()) {
                    return@KeyEventDispatcher true
                }
                if (!FocusPresence.hasAnyFocus) {
                    focusManager.moveFocus(FocusDirection.Next)
                }
            }
            true
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(dispatcher)
        onDispose {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(dispatcher)
        }
    }
}
