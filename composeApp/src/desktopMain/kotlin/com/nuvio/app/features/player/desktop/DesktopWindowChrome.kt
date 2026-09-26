package com.nuvio.app.features.player.desktop

import androidx.compose.ui.awt.ComposeWindow
import java.awt.Window

private const val NuvioWindowBackgroundRgb = 0x0D0D0D
private const val NuvioWindowTextRgb = 0xF5F7F8

/**
 * Configures Apple's native full-window-content title bar before AWT creates the
 * NSWindow peer. The native traffic-light controls and standard window behavior
 * stay owned by macOS, while the Compose content can render under the title bar.
 */
internal fun configureMacosWindowBeforePeer(window: ComposeWindow) {
    if (DesktopHostOs.current != DesktopHostOs.MACOS) return

    check(!window.isDisplayable) {
        "macOS window chrome must be configured before the native peer is created"
    }
    window.rootPane.apply {
        putClientProperty("apple.awt.fullWindowContent", true)
        putClientProperty("apple.awt.transparentTitleBar", true)
        putClientProperty("apple.awt.windowTitleVisible", false)
    }
}

internal fun applyNativeDesktopWindowChrome(window: Window) {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || !window.isDisplayable) return
    applyWindowsWindowChrome(window)
}

private fun applyWindowsWindowChrome(window: Window) {
    runCatching {
        NativePlayerBridge.applyWindowChrome(
            windowHwnd = AwtNativeViewResolver.resolveNativeViewPointer(window),
            darkMode = true,
            captionColorRgb = NuvioWindowBackgroundRgb,
            borderColorRgb = NuvioWindowBackgroundRgb,
            textColorRgb = NuvioWindowTextRgb,
        )
    }
}

// AWT's own first-show activation attempt (java.desktop's AwtFrame::WmShowWindow) is routinely
// denied by Windows' focus-stealing prevention once enough startup work has happened before the
// window appears, leaving the window merely visible with its taskbar button flashing instead of
// active. Force it to the foreground for real.
internal fun forceDesktopWindowForeground(window: Window) {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || !window.isDisplayable) return

    runCatching {
        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
        NativePlayerBridge.forceForegroundWindow(hwnd)
    }
}
