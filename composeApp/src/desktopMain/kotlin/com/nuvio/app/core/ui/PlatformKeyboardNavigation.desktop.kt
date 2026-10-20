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
            if (!focusManager.moveFocus(direction)) {
                focusManager.moveFocus(FocusDirection.Next)
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
