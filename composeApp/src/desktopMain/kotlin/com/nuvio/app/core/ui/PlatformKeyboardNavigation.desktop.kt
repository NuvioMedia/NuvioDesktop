package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import com.nuvio.app.controller.ControllerDirection
import com.nuvio.app.controller.ControllerEvent
import com.nuvio.app.controller.ControllerFocusEngine
import com.nuvio.app.controller.ControllerManager
import com.nuvio.app.controller.ControllerScreenType
import com.nuvio.app.controller.DesktopControllerInput
import com.nuvio.app.controller.XboxButton
import com.nuvio.app.controller.audio.ControllerSoundPlayer
import com.nuvio.app.controller.audio.DesktopControllerSound
import com.nuvio.app.features.player.PlayerCommand
import com.nuvio.app.features.player.PlayerControllerBridge
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

/**
 * Route generation whose screen already received a seeded Compose focus. Compose keeps previous
 * NavDisplay entries composed but unplaced, so focus can be parked on a screen the user has already
 * left; a directional move from there fails and the incoming screen never receives focus.
 */
private var seededComposeRouteGeneration = -1

/** Seeds the incoming screen exactly once per route change and moves focus as requested. */
private fun FocusManager.seedRouteFocusIfNeeded(): Boolean {
    val generation = ControllerFocusEngine.routeGeneration.value
    if (seededComposeRouteGeneration == generation) return false
    seededComposeRouteGeneration = generation
    clearFocus(force = true)
    return moveFocus(FocusDirection.Next)
}

/**
 * Resolves the AWT component that actually receives Compose key input.
 *
 * Compose registers its key listener on the Skia content component inside the app window, not on
 * the window itself. When the window is not the OS focus owner (a controller does not require
 * window focus) [KeyboardFocusManager.getFocusOwner] is null or points elsewhere, so the synthetic
 * Enter must be delivered to that content component directly.
 */
private fun resolveComposeInputComponent(): java.awt.Component? {
    val focusManager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
    focusManager.focusOwner?.let { return it }

    val window = java.awt.Window.getWindows().lastOrNull { it.isShowing && it.isActive && it.isFocusableWindow }
        ?: java.awt.Window.getWindows().lastOrNull { it.isShowing && it.isFocusableWindow }
        ?: return null

    fun deepestKeyTarget(component: java.awt.Component): java.awt.Component? {
        if (component is java.awt.Container) {
            component.components.forEach { child ->
                deepestKeyTarget(child)?.let { return it }
            }
        }
        return component.takeIf { it.isVisible && it.isFocusable && it.keyListeners.isNotEmpty() }
    }

    return deepestKeyTarget(window) ?: window
}

/**
 * Replays a gamepad A press as a real Enter key event, which is what Compose's clickable/button
 * keyboard handling listens for. This mirrors the keyboard path exactly, so text fields keep
 * consuming Enter themselves.
 */
private fun replayEnterAsKeyEvent() {
    val focusManager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val target = resolveComposeInputComponent() ?: return
    target.requestFocusInWindow()
    val timestamp = System.currentTimeMillis()
    focusManager.redispatchEvent(
        target,
        KeyEvent(target, KeyEvent.KEY_PRESSED, timestamp, 0, KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED),
    )
    focusManager.redispatchEvent(
        target,
        KeyEvent(target, KeyEvent.KEY_RELEASED, timestamp, 0, KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED),
    )
}

/**
 * Activates the Compose-focused control by replaying the gamepad A press as a real Enter key, which
 * is what Compose's clickable/button keyboard handling listens for. Compose's own focus keeps the
 * current item selected, so nothing is re-seeded here (that would move the user's focus).
 */
private fun FocusManager.activateSpatialFocus() {
    replayEnterAsKeyEvent()
}

/** Moves Compose focus for screens that are not instrumented with controllerFocusable. */
private fun FocusManager.moveSpatialFocus(direction: ControllerDirection): Boolean {
    val composeDirection = when (direction) {
        ControllerDirection.Left -> FocusDirection.Left
        ControllerDirection.Right -> FocusDirection.Right
        ControllerDirection.Up -> FocusDirection.Up
        ControllerDirection.Down -> FocusDirection.Down
    }
    if (moveFocus(composeDirection)) return true

    // Seed the incoming screen once per route change, then apply the requested move so the first
    // arrow press reacts instead of being swallowed (previously users had to press Tab).
    if (seedRouteFocusIfNeeded()) {
        return moveFocus(composeDirection)
    }
    return false
}

private val blankCursor: java.awt.Cursor by lazy {
    val transparent = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    java.awt.Toolkit.getDefaultToolkit().createCustomCursor(transparent, java.awt.Point(0, 0), "nuvio-blank")
}

/** Hides the pointer while the user navigates with a controller/keyboard; mouse input restores it. */
private fun applyNavigationCursor(hidden: Boolean) {
    val windows = java.awt.Window.getWindows().filter { it.isShowing && it.isFocusableWindow }
    if (windows.isEmpty()) return
    val cursor = if (hidden) blankCursor else java.awt.Cursor.getDefaultCursor()
    windows.forEach { window ->
        window.cursor = cursor
        resolveComposeInputComponent()?.cursor = cursor
    }
}

@Composable
actual fun PlatformKeyboardNavigationHost() {
    val focusManager = LocalFocusManager.current

    DesktopKeyboardShortcutHost()

    // Controller polling + navigation feedback sounds. DesktopControllerInput normalises XInput
    // (Windows) and SDL2 (Linux/macOS) into the same spatial-engine events.
    DisposableEffect(Unit) {
        DesktopControllerSound.install()
        DesktopControllerInput.start()
        onDispose {
            DesktopControllerInput.stop()
            DesktopControllerSound.uninstall()
        }
    }

    // A real mouse move or click means the user is no longer navigating with a controller or the
    // keyboard: hide focus rings and the controller hint toolbar.
    DisposableEffect(Unit) {
        val listener = AWTEventListener { event: AWTEvent ->
            if (event is MouseEvent) {
                when (event.id) {
                    MouseEvent.MOUSE_MOVED,
                    MouseEvent.MOUSE_DRAGGED,
                    MouseEvent.MOUSE_PRESSED,
                    MouseEvent.MOUSE_WHEEL,
                    -> ControllerManager.notifyMouseOrKeyboardInput()
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(
            listener,
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK,
        )
        onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }

    // Hide the mouse pointer while the user navigates with a controller or the keyboard; the first
    // real mouse movement switches navigation off again and restores it.
    LaunchedEffect(Unit) {
        ControllerManager.isNavigationActive.collect { active ->
            applyNavigationCursor(active)
        }
    }

    // Gamepad events the spatial engine cannot consume (profile picker, details, streams, settings,
    // player) are bridged into Compose focus so the controller behaves like the keyboard there.
    LaunchedEffect(focusManager) {
        ControllerManager.unhandledEvents.collect { event ->
            when (event) {
                is ControllerEvent.Navigate -> {
                    ControllerManager.notifyNavigationInput()
                    if (focusManager.moveSpatialFocus(event.direction)) {
                        ControllerSoundPlayer.playNavigate()
                    }
                }
                is ControllerEvent.ButtonPressed -> when (event.button) {
                    XboxButton.A -> {
                        ControllerSoundPlayer.playSelect()
                        focusManager.activateSpatialFocus()
                    }
                    // The engine routes B to registered global back handlers; when a screen does not
                    // register one, fall back to the same navigation back used by the Escape key.
                    XboxButton.B -> {
                        ControllerSoundPlayer.playBack()
                        DesktopEscapeShortcut.onBack?.invoke()
                    }
                    else -> Unit
                }
                else -> Unit
            }
        }
    }

    DisposableEffect(Unit) {
        val dispatcher = java.awt.KeyEventDispatcher { event: KeyEvent ->
            if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false

            val isPlayerActive = ControllerManager.screenContext.value.screenType ==
                ControllerScreenType.Player

            // Inside the player the arrows are playback controls, not focus movement.
            if (isPlayerActive) {
                if (event.keyCode == KeyEvent.VK_SPACE || event.keyCode == KeyEvent.VK_ENTER) {
                    if (PlayerControllerBridge.dispatch(PlayerCommand.TogglePlayback)) {
                        return@KeyEventDispatcher true
                    }
                }
            }

            // Enter activates the spatially focused item. Fall through to Compose when the engine
            // has nothing registered, so regular buttons keep their native Enter handling.
            if (event.keyCode == KeyEvent.VK_ENTER) {
                return@KeyEventDispatcher ControllerManager.dispatchKeyboardSelect()
            }

            val direction = when (event.keyCode) {
                KeyEvent.VK_LEFT -> ControllerDirection.Left
                KeyEvent.VK_RIGHT -> ControllerDirection.Right
                KeyEvent.VK_UP -> ControllerDirection.Up
                KeyEvent.VK_DOWN -> ControllerDirection.Down
                else -> return@KeyEventDispatcher false
            }

            if (isPlayerActive) {
                val command = when (direction) {
                    ControllerDirection.Left -> PlayerCommand.SeekBack
                    ControllerDirection.Right -> PlayerCommand.SeekForward
                    ControllerDirection.Up -> PlayerCommand.VolumeUp
                    ControllerDirection.Down -> PlayerCommand.VolumeDown
                }
                if (PlayerControllerBridge.dispatch(command)) {
                    ControllerManager.notifyNavigationInput()
                    ControllerSoundPlayer.playNavigate()
                    return@KeyEventDispatcher true
                }
            }

            // The spatial engine drives instrumented screens (home shelves, catalog grids, sidebar).
            if (ControllerManager.dispatchKeyboardNavigation(direction)) {
                return@KeyEventDispatcher true
            }

            // Screens without engine nodes (details, streams, settings, player) use Compose's own
            // directional focus search.
            ControllerManager.notifyNavigationInput()
            if (focusManager.moveSpatialFocus(direction)) {
                ControllerSoundPlayer.playNavigate()
            }
            true
        }
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(dispatcher)
        onDispose {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(dispatcher)
        }
    }
}
