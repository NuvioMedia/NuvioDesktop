package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import java.awt.Component
import java.awt.Container
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.TextComponent
import java.awt.event.KeyEvent
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.Scrollable

@Composable
actual fun PlatformKeyboardNavigation() {
    val focusManager = LocalFocusManager.current

    DisposableEffect(Unit) {
        val dispatcher = KeyEventDispatcher { event: KeyEvent ->
            if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            if (KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .focusOwner.isArrowKeyConsumer()
            ) return@KeyEventDispatcher false

            when (event.keyCode) {
                KeyEvent.VK_LEFT -> focusManager.moveFocus(FocusDirection.Left)
                KeyEvent.VK_RIGHT -> focusManager.moveFocus(FocusDirection.Right)
                KeyEvent.VK_UP -> focusManager.moveFocus(FocusDirection.Up)
                KeyEvent.VK_DOWN -> focusManager.moveFocus(FocusDirection.Down)
                else -> return@KeyEventDispatcher false
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

private fun Component?.isArrowKeyConsumer(): Boolean = when (this) {
    is JTextField -> true
    is JTextArea -> true
    is TextComponent -> true
    is JSpinner -> true
    is JSlider -> true
    is JScrollPane -> true
    is Container -> this is Scrollable
    else -> false
}
