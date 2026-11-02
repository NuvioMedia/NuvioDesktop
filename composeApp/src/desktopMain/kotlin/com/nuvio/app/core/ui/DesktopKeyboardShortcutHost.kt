package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

private const val ESCAPE_DEBOUNCE_MS = 300L

/**
 * Installs the desktop Escape shortcut and gives keyboard focus an entry point on app start.
 *
 * Without an initial focus target Compose Desktop has nothing to deliver arrow keys to, so the
 * first press would be dropped. Focusing the first available target on start is what makes the
 * screen keyboard-reachable without an extra press.
 */
@Composable
internal fun DesktopKeyboardShortcutHost() {
    val focusManager = LocalFocusManager.current
    val focusManagerState = rememberUpdatedState(focusManager)

    DisposableEffect(Unit) {
        var lastEscapeAtMs = 0L
        val dispatcher = KeyEventDispatcher { event: KeyEvent ->
            if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            if (event.keyCode != KeyEvent.VK_ESCAPE) return@KeyEventDispatcher false
            // IR remotes and held gamepad buttons repeat Esc; navigating twice would skip a
            // screen, so swallow presses inside the debounce window.
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastEscapeAtMs < ESCAPE_DEBOUNCE_MS) return@KeyEventDispatcher true
            lastEscapeAtMs = nowMs
            val back = DesktopEscapeShortcut.onBack ?: return@KeyEventDispatcher false
            back()
            true
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(dispatcher)

        focusManagerState.value.moveFocus(FocusDirection.Next)

        onDispose {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(dispatcher)
        }
    }
}