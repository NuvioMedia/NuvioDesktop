package org.openani.mediamp.mpv.utils

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser

internal class ChildHwndProvider(private val parentHwnd: Pointer) {
    private var hwnd: WinDef.HWND? = null

    companion object {
        private const val CLASS_NAME = "NuvioMpvChild"
        private var classRegistered = false
        private var moduleInstance: WinDef.HINSTANCE? = null

        @Synchronized
        fun ensureClassRegistered() {
            if (classRegistered) return
            val instance = Kernel32.INSTANCE.GetModuleHandle(null)
            moduleInstance = WinDef.HINSTANCE()
            moduleInstance!!.setPointer(instance.pointer)
            val wc = WinUser.WNDCLASSEX()
            wc.cbSize = wc.size().toInt()
            wc.style = 2 or 1 // CS_HREDRAW | CS_VREDRAW
            wc.lpfnWndProc = object : WinUser.WindowProc {
                override fun callback(
                    hwnd: WinDef.HWND?,
                    msg: Int,
                    wParam: WinDef.WPARAM?,
                    lParam: WinDef.LPARAM?,
                ): WinDef.LRESULT {
                    if (msg == 0x0084) { // WM_NCHITTEST
                        // HTTRANSPARENT = -1 — let mouse events pass through
                        // to the underlying Compose/Swing UI
                        return WinDef.LRESULT(-1)
                    }
                    val result = User32.INSTANCE.DefWindowProc(hwnd, msg, wParam, lParam)
                    return result ?: WinDef.LRESULT(0)
                }
            }
            wc.hInstance = moduleInstance
            wc.hCursor = null
            wc.hbrBackground = null
            wc.lpszClassName = CLASS_NAME
            val result = User32.INSTANCE.RegisterClassEx(wc)
            classRegistered = result.toInt() != 0
        }
    }

    fun create(x: Int, y: Int, width: Int, height: Int): Long {
        ensureClassRegistered()
        val w = width.coerceAtLeast(16)
        val h = height.coerceAtLeast(16)
        val hwndResult = User32.INSTANCE.CreateWindowEx(
            WinUser.WS_EX_TRANSPARENT,  // pass mouse events through to Compose controls underneath
            CLASS_NAME,
            null,
            WinUser.WS_CHILD or WinUser.WS_VISIBLE or WinUser.WS_CLIPSIBLINGS,
            x, y, w, h,
            WinDef.HWND(parentHwnd),
            null,
            moduleInstance,
            null
        )
        if (hwndResult == null || Pointer.nativeValue(hwndResult.getPointer()) == 0L) {
            println("MPV_CHILD_HWND CreateWindowEx FAILED x=$x y=$y w=$w h=$h")
            return 0L
        }
        hwnd = hwndResult
        val value = Pointer.nativeValue(hwndResult.getPointer())
        println("MPV_CHILD_HWND created hwnd=$value x=$x y=$y w=$w h=$h")
        return value
    }

    fun setPos(x: Int, y: Int, width: Int, height: Int) {
        val h = hwnd ?: return
        User32.INSTANCE.SetWindowPos(
            h,
            WinDef.HWND(Pointer(0)),  // HWND_TOP
            x, y, width.coerceAtLeast(1), height.coerceAtLeast(1),
            WinUser.SWP_NOACTIVATE
        )
    }

    fun destroy() {
        val h = hwnd ?: return
        println("MPV_CHILD_HWND destroying hwnd=${Pointer.nativeValue(h.getPointer())}")
        User32.INSTANCE.DestroyWindow(h)
        hwnd = null
    }

    fun destroyHwnd(hwndValue: Long) {
        if (hwndValue == 0L) return
        val ptr = Pointer(hwndValue)
        val h = WinDef.HWND()
        h.setPointer(ptr)
        println("MPV_CHILD_HWND destroying specific hwnd=$hwndValue")
        User32.INSTANCE.DestroyWindow(h)
    }
}
