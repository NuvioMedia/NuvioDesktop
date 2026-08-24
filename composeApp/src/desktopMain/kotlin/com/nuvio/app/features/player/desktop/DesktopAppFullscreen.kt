package com.nuvio.app.features.player.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import javax.swing.Timer
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
            toggleWindowsFullscreen(window)
        } else {
            toggleComposeFullscreen(window, windowState)
            if (DesktopHostOs.current == DesktopHostOs.LINUX) {
                enforceLinuxFullscreen(window, windowState)
            }
        }
    }

    /**
     * Compose applies [WindowState.placement] through updaters memoized on the
     * last value it applied, with a write-back listener that re-reads window
     * state on AWT window events. Some window managers (mutter) emit extra
     * state events that convince Compose the window is already windowed while
     * the X11 window still carries _NET_WM_STATE_FULLSCREEN — the exit write is
     * then skipped and the window stays fullscreen. Verify the AWT device state
     * against the intended placement and correct it; a no-op when Compose
     * applied the change itself.
     */
    private fun enforceLinuxFullscreen(window: Window, windowState: WindowState) {
        fun enforce(stage: String) {
            val device = window.graphicsConfiguration?.device ?: return
            val wantFullscreen = windowState.placement == WindowPlacement.Fullscreen
            val awtFullscreen = device.fullScreenWindow === window
            if (wantFullscreen == awtFullscreen) return
            device.fullScreenWindow = if (wantFullscreen) window else null
        }
        SwingUtilities.invokeLater { enforce("immediate") }
        Timer(250) { enforce("delayed") }.apply { isRepeats = false }.start()
    }

    fun dispose(window: Window) {
        exitWindowsFullscreen(window)
    }

    /**
     * Applies a fullscreen state restored from a previous session, before the
     * window has been interacted with. Only acts when [fullscreen] is true;
     * windowed is already the default state for a freshly created window.
     */
    fun applyRestoredFullscreenState(window: Window, windowState: WindowState, fullscreen: Boolean) {
        if (!fullscreen) return
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
            enterWindowsFullscreen(window)
        } else {
            restoreWindowPlacement = windowState.placement
                .takeUnless { it == WindowPlacement.Fullscreen }
                ?: WindowPlacement.Floating
            windowState.placement = WindowPlacement.Fullscreen
        }
    }

    fun isFullscreen(window: Window, windowState: WindowState): Boolean =
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
            windowsFullscreenState?.window === window
        } else {
            windowState.placement == WindowPlacement.Fullscreen
        }

    private fun toggleComposeFullscreen(window: Window, windowState: WindowState) {
        if (isFullscreen(window, windowState)) {
            if (DesktopHostOs.current == DesktopHostOs.MACOS) {
                applyMacosComposeFullscreenExit(
                    restorePlacement = restoreWindowPlacement,
                    requestNativeFullscreenExit = { requestNativeComposeFullscreenExit(window) },
                    clearComposeFullscreen = {
                        (window as? ComposeWindow)?.placement = WindowPlacement.Floating
                    },
                    setStatePlacement = { placement ->
                        windowState.placement = placement
                    },
                )
            } else {
                windowState.placement = restoreWindowPlacement
            }
        } else {
            restoreWindowPlacement = windowState.placement
                .takeUnless { it == WindowPlacement.Fullscreen }
                ?: WindowPlacement.Floating
            windowState.placement = WindowPlacement.Fullscreen
        }
    }

    private fun requestNativeComposeFullscreenExit(window: Window): Boolean {
        if (DesktopHostOs.current != DesktopHostOs.MACOS) return false
        return runCatching {
            NativePlayerBridge.setMacosWindowFullscreen(
                windowViewPtr = AwtNativeViewResolver.resolveNativeViewPointer(window),
                fullscreen = false,
            )
        }.isSuccess
    }

    private fun toggleWindowsFullscreen(window: Window) {
        if (windowsFullscreenState?.window === window) {
            exitWindowsFullscreen(window)
        } else {
            enterWindowsFullscreen(window)
        }
    }

    private fun enterWindowsFullscreen(window: Window) {
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
        windowsFullscreenState = WindowsFullscreenState(window = window, windowHwnd = hwnd)
        window.toFront()
        window.requestFocus()
    }

    private fun exitWindowsFullscreen(window: Window) {
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
    }

    private data class WindowsFullscreenState(
        val window: Window,
        val windowHwnd: Long,
    )
}

/**
 * ComposeWindow does not clear its fullscreen flag when placement is changed directly from
 * Fullscreen to Maximized on macOS. Let AppKit complete its asynchronous fullscreen exit and let
 * Compose's native window listener restore WindowState; writing Maximized during that transition
 * can alter the frame AppKit is restoring. The Compose fallback is only used if the native macOS
 * request cannot be made.
 */
internal fun applyMacosComposeFullscreenExit(
    restorePlacement: WindowPlacement,
    requestNativeFullscreenExit: () -> Boolean,
    clearComposeFullscreen: () -> Unit,
    setStatePlacement: (WindowPlacement) -> Unit,
) {
    if (requestNativeFullscreenExit()) return

    val targetPlacement = restorePlacement
        .takeUnless { it == WindowPlacement.Fullscreen }
        ?: WindowPlacement.Floating
    clearComposeFullscreen()
    setStatePlacement(targetPlacement)
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
