package com.nuvio.app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Toolkit
import javax.swing.SwingUtilities

internal object DesktopBorderlessFullscreenController {
    private const val GWL_STYLE = -16
    private const val GWL_EXSTYLE = -20

    private const val WS_CAPTION = 0x00C00000L
    private const val WS_SYSMENU = 0x00080000L
    private const val WS_THICKFRAME = 0x00040000L
    private const val WS_MINIMIZEBOX = 0x00020000L
    private const val WS_MAXIMIZEBOX = 0x00010000L
    private const val WS_POPUP = 0x80000000L
    private const val WS_OVERLAPPEDWINDOW = WS_CAPTION or WS_SYSMENU or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX

    private const val WS_EX_DLGMODALFRAME = 0x00000001L
    private const val WS_EX_WINDOWEDGE = 0x00000100L
    private const val WS_EX_CLIENTEDGE = 0x00000200L
    private const val WS_EX_STATICEDGE = 0x00020000L

    private const val SWP_NOOWNERZORDER = 0x0200
    private const val SWP_FRAMECHANGED = 0x0020
    private const val SWP_SHOWWINDOW = 0x0040
    private const val HWND_TOPMOST = -1

    private const val FULLSCREEN_DEBOUNCE_MS = 300L

    private val isWindows: Boolean
        get() = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true

    private var snapshot: FullscreenSnapshot? = null
    private val topmostHwnd = Pointer(HWND_TOPMOST.toLong())
    private val user32: User32? by lazy {
        runCatching { Native.load("user32", User32::class.java) }
            .onFailure { DesktopRuntimeLog.error("borderlessFullscreen: cannot load user32", it) }
            .getOrNull()
    }

    private var lastToggleTime = 0L

    var revision by mutableIntStateOf(0)
        private set

    val isFullscreenActive: Boolean
        get() = snapshot != null

    fun toggle(window: ComposeWindow) {
        val now = System.currentTimeMillis()
        if (now - lastToggleTime < FULLSCREEN_DEBOUNCE_MS) {
            DesktopRuntimeLog.info("borderlessFullscreen: toggle debounced")
            return
        }
        lastToggleTime = now

        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { toggle(window) }
            return
        }

        if (isFullscreen(window)) {
            exit(window)
        } else {
            enter(window)
        }
    }

    fun enter(window: ComposeWindow) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { enter(window) }
            return
        }

        if (!isWindows) {
            enterComposeFullscreen(window)
            return
        }
        if (isFullscreen(window)) return

        val handle = resolveHandle(window)
        val native = user32
        if (handle == null || native == null) {
            DesktopRuntimeLog.warn("borderlessFullscreen: native handle unavailable, falling back to Compose fullscreen")
            enterComposeFullscreen(window)
            return
        }

        val currentStyle = native.getWindowLongPtr(handle, GWL_STYLE)
        val currentExStyle = native.getWindowLongPtr(handle, GWL_EXSTYLE)
        val previousBounds = Rectangle(window.bounds)
        val targetBounds = window.currentScreenBounds()

        snapshot = FullscreenSnapshot(
            window = window,
            placement = window.placement,
            extendedState = window.extendedState,
            bounds = previousBounds,
            style = currentStyle,
            exStyle = currentExStyle,
            mode = FullscreenMode.WindowsBorderless,
        )

        runCatching {
            window.placement = WindowPlacement.Floating
            window.extendedState = window.extendedState and Frame.MAXIMIZED_BOTH.inv()
            native.setWindowLongPtr(handle, GWL_STYLE, (currentStyle and WS_OVERLAPPEDWINDOW.inv()) or WS_POPUP)
            native.setWindowLongPtr(
                handle,
                GWL_EXSTYLE,
                currentExStyle and (WS_EX_DLGMODALFRAME or WS_EX_WINDOWEDGE or WS_EX_CLIENTEDGE or WS_EX_STATICEDGE).inv(),
            )
            native.applyFrameBounds(handle, targetBounds)
            window.bounds = targetBounds
            window.toFront()
            window.requestFocus()
            Toolkit.getDefaultToolkit().sync()
            window.repaint()
            window.requestFocus()
        }.onSuccess {
            DesktopRuntimeLog.info(
                "borderlessFullscreen: entered bounds=${targetBounds.shortLog()} previousPlacement=${snapshot?.placement}",
            )
            Toolkit.getDefaultToolkit().sync()
            bumpRevision()
        }.onFailure {
            DesktopRuntimeLog.error("borderlessFullscreen: enter failed, restoring window state", it)
            restoreSnapshot(window)
            enterComposeFullscreen(window)
        }
    }

    fun exit(window: ComposeWindow) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { exit(window) }
            return
        }

        val active = snapshot
        if (active?.window === window) {
            restoreSnapshot(window)
            DesktopRuntimeLog.info("borderlessFullscreen: exited mode=${active.mode}")
            Toolkit.getDefaultToolkit().sync()
            window.repaint()
            Toolkit.getDefaultToolkit().sync()
            bumpRevision()
            return
        }

        val device = window.graphicsConfiguration?.device
        if (device?.fullScreenWindow === window) {
            device.fullScreenWindow = null
        }
        if (window.placement == WindowPlacement.Fullscreen) {
            window.placement = WindowPlacement.Floating
            window.extendedState = window.extendedState and Frame.MAXIMIZED_BOTH.inv()
            DesktopRuntimeLog.info("borderlessFullscreen: exited legacy Compose fullscreen")
            Toolkit.getDefaultToolkit().sync()
            window.repaint()
            bumpRevision()
        }
    }

    fun isFullscreen(window: ComposeWindow): Boolean {
        val device = window.graphicsConfiguration?.device
        return snapshot?.window === window ||
            window.placement == WindowPlacement.Fullscreen ||
            device?.fullScreenWindow === window
    }

    private fun enterComposeFullscreen(window: ComposeWindow) {
        snapshot = FullscreenSnapshot(
            window = window,
            placement = window.placement.takeIf { it != WindowPlacement.Fullscreen } ?: WindowPlacement.Floating,
            extendedState = window.extendedState,
            bounds = Rectangle(window.bounds),
            style = null,
            exStyle = null,
            mode = FullscreenMode.ComposeFallback,
        )

        window.placement = WindowPlacement.Fullscreen
        DesktopRuntimeLog.warn("borderlessFullscreen: entered Compose fullscreen fallback")
        bumpRevision()
    }

    private fun restoreSnapshot(window: ComposeWindow) {
        val active = snapshot ?: return
        snapshot = null

        val handle = resolveHandle(window)
        val native = user32
        val restoreBoundsFirst = active.placement != WindowPlacement.Maximized &&
            active.extendedState and Frame.MAXIMIZED_BOTH == 0

        if (handle != null && native != null && active.style != null && active.exStyle != null) {
            native.setWindowLongPtr(handle, GWL_STYLE, active.style)
            native.setWindowLongPtr(handle, GWL_EXSTYLE, active.exStyle)
            native.applyFrameBounds(handle, if (restoreBoundsFirst) active.bounds else window.bounds)
        }

        val device = window.graphicsConfiguration?.device
        if (device?.fullScreenWindow === window) {
            device.fullScreenWindow = null
        }

        window.placement = WindowPlacement.Floating
        window.extendedState = active.extendedState and Frame.MAXIMIZED_BOTH.inv()
        if (restoreBoundsFirst) {
            window.bounds = active.bounds
        }

        if (active.placement == WindowPlacement.Maximized ||
            active.extendedState and Frame.MAXIMIZED_BOTH != 0
        ) {
            window.placement = WindowPlacement.Maximized
            window.extendedState = active.extendedState or Frame.MAXIMIZED_BOTH
        }
        window.repaint()
    }

    private fun resolveHandle(window: ComposeWindow): Pointer? =
        runCatching { Native.getWindowPointer(window) }
            .onFailure { DesktopRuntimeLog.warn("borderlessFullscreen: cannot resolve HWND ${it.message}") }
            .getOrNull()

    private fun ComposeWindow.currentScreenBounds(): Rectangle {
        val bounds = graphicsConfiguration?.bounds
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            return Rectangle(bounds)
        }
        val size = Toolkit.getDefaultToolkit().screenSize
        return Rectangle(0, 0, size.width, size.height)
    }

    private fun User32.getWindowLongPtr(handle: Pointer, index: Int): Long =
        if (Native.POINTER_SIZE == 8) {
            GetWindowLongPtrW(handle, index)
        } else {
            GetWindowLongW(handle, index).toLong()
        }

    private fun User32.setWindowLongPtr(handle: Pointer, index: Int, value: Long) {
        if (Native.POINTER_SIZE == 8) {
            SetWindowLongPtrW(handle, index, value)
        } else {
            SetWindowLongW(handle, index, value.toInt())
        }
    }

    private fun User32.applyFrameBounds(handle: Pointer, bounds: Rectangle) {
        SetWindowPos(
            handle,
            topmostHwnd,
            bounds.x,
            bounds.y,
            bounds.width,
            bounds.height,
            SWP_NOOWNERZORDER or SWP_FRAMECHANGED or SWP_SHOWWINDOW,
        )
    }

    private fun bumpRevision() {
        revision += 1
    }

    private fun Rectangle.shortLog(): String = "${x},${y} ${width}x${height}"

    private enum class FullscreenMode {
        WindowsBorderless,
        ComposeFallback,
    }

    private data class FullscreenSnapshot(
        val window: ComposeWindow,
        val placement: WindowPlacement,
        val extendedState: Int,
        val bounds: Rectangle,
        val style: Long?,
        val exStyle: Long?,
        val mode: FullscreenMode,
    )

    private interface User32 : StdCallLibrary, Library {
        fun GetWindowLongW(hWnd: Pointer, nIndex: Int): Int
        fun SetWindowLongW(hWnd: Pointer, nIndex: Int, dwNewLong: Int): Int
        fun GetWindowLongPtrW(hWnd: Pointer, nIndex: Int): Long
        fun SetWindowLongPtrW(hWnd: Pointer, nIndex: Int, dwNewLong: Long): Long
        fun SetWindowPos(
            hWnd: Pointer,
            hWndInsertAfter: Pointer?,
            x: Int,
            y: Int,
            cx: Int,
            cy: Int,
            uFlags: Int,
        ): Boolean
    }
}
