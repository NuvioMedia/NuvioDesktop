package com.nuvio.app.features.player.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Window
import java.awt.event.MouseEvent
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val NuvioWindowBackgroundRgb = 0x0D0D0D
private const val NuvioWindowTextRgb = 0xF5F7F8

internal val MacosTitleBarHeight = 28.dp

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

/**
 * With full-window content the Compose view sits under the transparent title bar,
 * so AppKit still drags the window from that strip but never runs its own
 * double-click handler. Replays the system title-bar double-click action for
 * double-clicks in the strip that no Compose control consumed.
 */
internal fun Modifier.macosTitleBarDoubleClickAction(
    windowState: WindowState,
    enabled: Boolean,
): Modifier {
    if (DesktopHostOs.current != DesktopHostOs.MACOS || !enabled) return this
    return pointerInput(windowState) {
        coroutineScope {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.singleOrNull() ?: continue
                    val isTitleBarDoubleClick = event.type == PointerEventType.Press &&
                        event.buttons.isPrimaryPressed &&
                        event.awtEventOrNull?.button == MouseEvent.BUTTON1 &&
                        event.awtEventOrNull?.clickCount == 2 &&
                        !change.isConsumed &&
                        change.position.y < MacosTitleBarHeight.toPx()
                    if (!isTitleBarDoubleClick) continue
                    change.consume()
                    launch { performMacosTitleBarDoubleClickAction(windowState) }
                }
            }
        }
    }
}

private enum class MacosTitleBarDoubleClickAction { ZOOM, MINIMIZE, NONE }

private suspend fun performMacosTitleBarDoubleClickAction(windowState: WindowState) {
    val action = withContext(Dispatchers.IO) { readMacosTitleBarDoubleClickAction() }
    // The window may have entered fullscreen or been minimized while the preference was read.
    if (windowState.placement == WindowPlacement.Fullscreen || windowState.isMinimized) return
    when (action) {
        MacosTitleBarDoubleClickAction.ZOOM -> {
            windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                WindowPlacement.Floating
            } else {
                WindowPlacement.Maximized
            }
        }
        MacosTitleBarDoubleClickAction.MINIMIZE -> windowState.isMinimized = true
        MacosTitleBarDoubleClickAction.NONE -> Unit
    }
}

// Read on every double-click so a change in System Settings applies without a relaunch.
// "Fill" (macOS 15+) and the older "Maximize" both map to the window's zoom.
private fun readMacosTitleBarDoubleClickAction(): MacosTitleBarDoubleClickAction {
    val action = readMacosGlobalDefault("AppleActionOnDoubleClick")?.lowercase(Locale.ROOT)
    return when {
        action == "minimize" -> MacosTitleBarDoubleClickAction.MINIMIZE
        action == "none" -> MacosTitleBarDoubleClickAction.NONE
        action != null -> MacosTitleBarDoubleClickAction.ZOOM
        readMacosGlobalDefault("AppleMiniaturizeOnDoubleClick") == "1" -> MacosTitleBarDoubleClickAction.MINIMIZE
        else -> MacosTitleBarDoubleClickAction.ZOOM
    }
}

private fun readMacosGlobalDefault(key: String): String? = runCatching {
    val process = ProcessBuilder("/usr/bin/defaults", "read", "-g", key)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    try {
        // Wait before reading: a stalled process would otherwise block readText() forever.
        // The single-line value fits in the pipe buffer, so the process never blocks on output.
        if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) return@runCatching null
        process.inputStream.bufferedReader().use { it.readText() }.trim().takeIf { it.isNotEmpty() }
    } finally {
        process.destroyForcibly()
    }
}.getOrNull()

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
