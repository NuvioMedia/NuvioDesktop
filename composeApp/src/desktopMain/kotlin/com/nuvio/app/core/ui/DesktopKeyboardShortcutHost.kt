package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
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
    val navigationEventOwner = LocalNavigationEventDispatcherOwner.current
    val backInput = remember { DirectNavigationEventInput() }

    DisposableEffect(navigationEventOwner) {
        val navigationDispatcher = navigationEventOwner?.navigationEventDispatcher
        navigationDispatcher?.addInput(backInput)
        DesktopEscapeShortcut.composeBack = {
            if (navigationDispatcher == null || !navigationDispatcher.isEnabled) {
                false
            } else {
                backInput.backStarted(NavigationEvent())
                backInput.backCompleted()
                true
            }
        }
        onDispose {
            DesktopEscapeShortcut.composeBack = null
            navigationDispatcher?.removeInput(backInput)
        }
    }

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
            DesktopEscapeShortcut.dispatch()
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