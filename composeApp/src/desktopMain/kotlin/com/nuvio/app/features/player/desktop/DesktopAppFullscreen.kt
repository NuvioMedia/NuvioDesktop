package com.nuvio.app.features.player.desktop

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private object DesktopAppFullscreen {
    private var toggleHandler: ((Window?) -> Unit)? = null
    private var fullscreenStateProvider: ((Window?) -> Boolean)? = null
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun setToggleHandler(
        handler: ((Window?) -> Unit)?,
        isFullscreen: (Window?) -> Boolean,
    ): () -> Unit {
        toggleHandler = handler
        fullscreenStateProvider = isFullscreen
        notifyChanged()
        return {
            if (toggleHandler === handler) {
                toggleHandler = null
                fullscreenStateProvider = null
                notifyChanged()
            }
        }
    }

    fun toggle(window: Window? = null) {
        val handler = toggleHandler ?: return
        if (SwingUtilities.isEventDispatchThread()) {
            handler(window)
            notifyChanged()
        } else {
            SwingUtilities.invokeLater {
                handler(window)
                notifyChanged()
            }
        }
    }

    fun isFullscreen(window: Window? = null): Boolean =
        fullscreenStateProvider?.invoke(window) == true

    private fun notifyChanged() {
        _changes.value += 1
    }
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

internal val desktopFullscreenChanges: StateFlow<Int>
    get() = DesktopAppFullscreen.changes

internal class DesktopAppFullscreenController {
    private var restoreWindowPlacement = WindowPlacement.Floating
    private var windowsFullscreenState: WindowsFullscreenState? = null

    fun toggle(window: Window, windowState: WindowState) {
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
            toggleWindowsFullscreen(window, windowState)
        } else {
            toggleComposeFullscreen(windowState)
        }
    }

    fun dispose(window: Window) {
        exitWindowsFullscreen(window)
    }

    fun isFullscreen(window: Window, windowState: WindowState): Boolean =
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
            windowsFullscreenState?.window === window
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

    private fun toggleWindowsFullscreen(window: Window, windowState: WindowState) {
        if (windowsFullscreenState?.window === window) {
            exitWindowsFullscreen(window, windowState)
        } else {
            enterWindowsFullscreen(window, windowState)
        }
    }

    private fun enterWindowsFullscreen(window: Window, windowState: WindowState) {
        val restorePlacement = window.restorePlacement(windowState)
        if (restorePlacement == WindowPlacement.Maximized) {
            (window as? Frame)?.maximizedBounds = window.workAreaBounds()
        }

        val gc = window.graphicsConfiguration
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val screenBounds = gc.bounds
        val transform = gc.defaultTransform
        val scaleX = transform.scaleX
        val scaleY = transform.scaleY

        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
        NativePlayerBridge.setWindowBorderlessFullscreen(
            windowHwnd = hwnd,
            fullscreen = true,
            x = (screenBounds.x * scaleX).toInt(),
            y = (screenBounds.y * scaleY).toInt(),
            width = (screenBounds.width * scaleX).toInt(),
            height = (screenBounds.height * scaleY).toInt(),
        )
        windowsFullscreenState = WindowsFullscreenState(
            window = window,
            windowHwnd = hwnd,
            restorePlacement = restorePlacement,
        )
        window.toFront()
        window.requestFocus()
    }

    private fun exitWindowsFullscreen(window: Window, windowState: WindowState? = null) {
        val fullscreenState = windowsFullscreenState?.takeIf { it.window === window } ?: return
        NativePlayerBridge.setWindowBorderlessFullscreen(
            windowHwnd = fullscreenState.windowHwnd,
            fullscreen = false,
            x = 0,
            y = 0,
            width = 0,
            height = 0,
        )
        windowsFullscreenState = null
        window.restorePlacementAfterNativeFullscreen(windowState, fullscreenState.restorePlacement)
    }

    private data class WindowsFullscreenState(
        val window: Window,
        val windowHwnd: Long,
        val restorePlacement: WindowPlacement,
    )
}

private fun Window.restorePlacement(windowState: WindowState): WindowPlacement {
    val frame = this as? Frame
    val isNativeMaximized = frame?.extendedState?.let { state ->
        state and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
    } == true
    if (isNativeMaximized) return WindowPlacement.Maximized
    return windowState.placement.takeUnless { it == WindowPlacement.Fullscreen }
        ?: WindowPlacement.Floating
}

private fun Window.restorePlacementAfterNativeFullscreen(
    windowState: WindowState?,
    placement: WindowPlacement,
) {
    SwingUtilities.invokeLater {
        val frame = this as? Frame
        if (placement == WindowPlacement.Maximized && frame != null) {
            val workArea = workAreaBounds()
            frame.maximizedBounds = workArea
            frame.extendedState = frame.extendedState or Frame.MAXIMIZED_BOTH
            windowState?.placement = WindowPlacement.Maximized

            SwingUtilities.invokeLater {
                if (frame.bounds.exceeds(workArea)) {
                    frame.extendedState = frame.extendedState and Frame.MAXIMIZED_BOTH.inv()
                    frame.bounds = workArea
                    frame.extendedState = frame.extendedState or Frame.MAXIMIZED_BOTH
                }
                frame.invalidate()
                frame.validate()
                frame.repaint()
            }
        } else {
            windowState?.placement = placement
        }
    }
}

private fun Window.workAreaBounds(): Rectangle {
    val gc = graphicsConfiguration
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val bounds = gc.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    return Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        (bounds.width - insets.left - insets.right).coerceAtLeast(1),
        (bounds.height - insets.top - insets.bottom).coerceAtLeast(1),
    )
}

private fun Rectangle.exceeds(bounds: Rectangle): Boolean =
    x < bounds.x ||
        y < bounds.y ||
        x + width > bounds.x + bounds.width ||
        y + height > bounds.y + bounds.height

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
