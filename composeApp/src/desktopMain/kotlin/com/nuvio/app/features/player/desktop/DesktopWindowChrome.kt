package com.nuvio.app.features.player.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window

private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_CAPTION_COLOR = 35
private const val DWMWA_TEXT_COLOR = 36
private const val DWMWA_BORDER_COLOR = 34
private const val NUVIO_DARK_RGB = 0x0D0D0D
private const val NUVIO_TEXT_RGB = 0xF5F7F8

private val dwmapi: Dwmapi? by lazy {
    runCatching { Native.load("dwmapi", Dwmapi::class.java) }
        .onFailure { println("DWM_CHROME: cannot load dwmapi: ${it.message}") }
        .getOrNull()
}

internal fun applyNativeDesktopWindowChrome(window: Window) {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || !window.isDisplayable) return
    val api = dwmapi ?: return
    val hwnd = resolveHwnd(window) ?: return

    runCatching {
        api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, intToPtr(1), 4)
        api.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, intToPtr(NUVIO_DARK_RGB), 4)
        api.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, intToPtr(NUVIO_TEXT_RGB), 4)
        api.DwmSetWindowAttribute(hwnd, DWMWA_BORDER_COLOR, intToPtr(NUVIO_DARK_RGB), 4)
    }.onFailure {
        println("DWM_CHROME: DwmSetWindowAttribute failed: ${it.message}")
    }
}

internal fun applyFullscreenBounds(window: Window) {
    val screen = window.graphicsConfiguration ?: return
    val bounds = getUsableScreenBounds(screen)
    window.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
    window.validate()
}

internal fun applyNormalBounds(window: Window, savedBounds: Rectangle) {
    window.setBounds(savedBounds.x, savedBounds.y, savedBounds.width, savedBounds.height)
    window.validate()
}

internal fun getUsableScreenBounds(screen: GraphicsConfiguration): Rectangle {
    val deviceBounds = screen.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(screen)
    return Rectangle(
        deviceBounds.x + insets.left,
        deviceBounds.y + insets.top,
        deviceBounds.width - insets.left - insets.right,
        deviceBounds.height - insets.top - insets.bottom,
    )
}

internal fun monitorCount(): Int =
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.size

private fun resolveHwnd(window: Window): Pointer? =
    runCatching { Native.getWindowPointer(window) }
        .onFailure { println("DWM_CHROME: cannot resolve HWND: ${it.message}") }
        .getOrNull()

private fun intToPtr(value: Int): Pointer {
    val mem = Memory(4)
    mem.setInt(0, value)
    return mem
}

private interface Dwmapi : StdCallLibrary, Library {
    fun DwmSetWindowAttribute(hwnd: Pointer, dwAttribute: Int, pvAttribute: Pointer, cbAttribute: Int): Int
}
