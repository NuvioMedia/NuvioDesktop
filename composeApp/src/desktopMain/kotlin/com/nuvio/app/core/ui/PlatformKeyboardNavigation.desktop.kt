package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

@Composable
actual fun PlatformKeyboardNavigation() {
    val focusManager = LocalFocusManager.current

    DisposableEffect(Unit) {
        val dispatcher = KeyEventDispatcher { event: KeyEvent ->
            if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            when (event.keyCode) {
                KeyEvent.VK_LEFT -> { focusManager.moveFocus(FocusDirection.Left); true }
                KeyEvent.VK_RIGHT -> { focusManager.moveFocus(FocusDirection.Right); true }
                KeyEvent.VK_UP -> { focusManager.moveFocus(FocusDirection.Up); true }
                KeyEvent.VK_DOWN -> { focusManager.moveFocus(FocusDirection.Down); true }
                else -> false
            }
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(dispatcher)
        onDispose {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(dispatcher)
        }
    }
}
