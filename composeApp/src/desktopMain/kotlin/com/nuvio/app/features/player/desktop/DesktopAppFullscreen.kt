package com.nuvio.app.features.player.desktop

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

internal object DesktopAppNavigation {
    var currentBackHandler: (() -> Unit)? = null
}

private object DesktopAppFullscreen {
    private var toggleHandler: ((Window?) -> Unit)? = null
    private var fullscreenStateProvider: ((Window?) -> Boolean)? = null

    fun setToggleHandler(
        handler: ((Window?) -> Unit)?,
        isFullscreen: (Window?) -> Boolean,
    ): () -> Unit {
        toggleHandler = handler
        fullscreenStateProvider = isFullscreen
        return {
            if (toggleHandler === handler) {
                toggleHandler = null
                fullscreenStateProvider = null
            }
        }
    }

    fun toggle(window: Window? = null) {
        val handler = toggleHandler ?: return
        if (SwingUtilities.isEventDispatchThread()) {
            handler(window)
        } else {
            SwingUtilities.invokeLater { handler(window) }
        }
    }

    fun isFullscreen(window: Window? = null): Boolean =
        fullscreenStateProvider?.invoke(window) == true
}

internal fun registerDesktopAppFullscreenToggle(
    handler: (Window?) -> Unit,
    isFullscreen: (Window?) -> Boolean,
): () -> Unit =
    DesktopAppFullscreen.setToggleHandler(handler, isFullscreen)

internal fun toggleDesktopAppFullscreen(window: Window? = null) {
    DesktopAppFullscreen.toggle(window)
}

internal fun isDesktopAppFullscreen(window: Window? = null): Boolean =
    DesktopAppFullscreen.isFullscreen(window)

internal class DesktopAppFullscreenController {
    private var restoreWindowPlacement = WindowPlacement.Floating
    private var windowsRestoreState: WindowsRestoreState? = null

    fun toggle(window: Window, windowState: WindowState) {
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
            toggleWindowsFullscreen(window)
        } else {
            toggleComposeFullscreen(windowState)
        }
    }

    fun dispose(window: Window) {
        exitWindowsFullscreen(window)
    }

    fun isFullscreen(window: Window, windowState: WindowState): Boolean =
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
            windowsRestoreState?.let { true } == true
        } else {
            windowState.placement == WindowPlacement.Fullscreen
        }

    private fun toggleComposeFullscreen(windowState: WindowState) {
        if (windowState.placement == WindowPlacement.Fullscreen) {
            windowState.placement = restoreWindowPlacement
        } else {
            restoreWindowPlacement = windowState.placement
                .takeUnless { it == WindowPlacement.Fullscreen }
                ?: WindowPlacement.Floating
            windowState.placement = WindowPlacement.Fullscreen
        }
    }

    private fun toggleWindowsFullscreen(window: Window) {
        if (activeFullscreenDevice(window) != null) {
            exitWindowsFullscreen(window)
        } else {
            enterWindowsFullscreen(window)
        }
    }

    private fun enterWindowsFullscreen(window: Window) {
        val device = window.graphicsConfiguration?.device
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        windowsRestoreState = WindowsRestoreState(
            device = device,
            bounds = Rectangle(window.bounds),
            frameState = (window as? Frame)?.extendedState,
        )
        device.fullScreenWindow = window
        window.toFront()
        window.requestFocus()
    }

    private fun exitWindowsFullscreen(window: Window) {
        val restoreState = windowsRestoreState
        val device = activeFullscreenDevice(window) ?: restoreState?.device ?: return
        if (device.fullScreenWindow === window) {
            device.fullScreenWindow = null
        }
        val frame = window as? Frame
        if (frame != null && restoreState?.frameState != null) {
            frame.extendedState = restoreState.frameState
        }
        if (restoreState != null && frame?.extendedState != Frame.MAXIMIZED_BOTH) {
            window.bounds = restoreState.bounds
        }
        if (windowsRestoreState?.device === restoreState?.device) {
            windowsRestoreState = null
        }
    }

    private fun activeFullscreenDevice(window: Window): GraphicsDevice? {
        windowsRestoreState?.device
            ?.takeIf { it.fullScreenWindow === window }
            ?.let { return it }
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices
            .firstOrNull { it.fullScreenWindow === window }
    }

    private data class WindowsRestoreState(
        val device: GraphicsDevice,
        val bounds: Rectangle,
        val frameState: Int?,
    )
}

internal fun installDesktopAppFullscreenShortcuts(window: Window): () -> Unit {
    val dispatcher = KeyEventDispatcher { event ->
        if (!event.isDesktopAppFullscreenShortcut()) return@KeyEventDispatcher false
        toggleDesktopAppFullscreen(window)
        true
    }
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
    return {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
    }
}

private fun KeyEvent.isDesktopAppFullscreenShortcut(): Boolean {
    if (id != KeyEvent.KEY_PRESSED) return false
    if (keyCode == KeyEvent.VK_F11) return true
    if (keyCode != KeyEvent.VK_F) return false
    val modifiers = modifiersEx
    val hasMacFullscreenModifiers =
        modifiers and KeyEvent.META_DOWN_MASK != 0 &&
            modifiers and KeyEvent.CTRL_DOWN_MASK != 0 &&
            modifiers and KeyEvent.ALT_DOWN_MASK == 0
    return hasMacFullscreenModifiers
}

internal fun installDesktopMouseButtonShortcuts(window: Window): () -> Unit {
    val listener = AWTEventListener { awtEvent ->
        if (awtEvent.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
        val mouseEvent = awtEvent as? MouseEvent ?: return@AWTEventListener
        if (mouseEvent.button !in 4..5) return@AWTEventListener
        if (!window.isFocused) return@AWTEventListener
        val backHandler = DesktopAppNavigation.currentBackHandler
        if (backHandler != null) {
            backHandler()
            return@AWTEventListener
        }
        val now = System.currentTimeMillis()
        val pressed = KeyEvent(
            window,
            KeyEvent.KEY_PRESSED,
            now,
            0,
            KeyEvent.VK_ESCAPE,
            KeyEvent.CHAR_UNDEFINED,
            KeyEvent.KEY_LOCATION_UNKNOWN,
        )
        val released = KeyEvent(
            window,
            KeyEvent.KEY_RELEASED,
            now,
            0,
            KeyEvent.VK_ESCAPE,
            KeyEvent.CHAR_UNDEFINED,
            KeyEvent.KEY_LOCATION_UNKNOWN,
        )
        java.awt.EventQueue.invokeLater {
            Toolkit.getDefaultToolkit().systemEventQueue.postEvent(pressed)
            Toolkit.getDefaultToolkit().systemEventQueue.postEvent(released)
        }
    }
    Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
    return {
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
    }
}
