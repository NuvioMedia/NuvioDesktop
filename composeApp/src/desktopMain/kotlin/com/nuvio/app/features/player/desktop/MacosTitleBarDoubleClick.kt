package com.nuvio.app.features.player.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import java.util.concurrent.TimeUnit

/**
 * macOS keeps a hollow title bar because the window runs with
 * `apple.awt.fullWindowContent`: Compose draws under the traffic lights and the native
 * double-click-to-zoom never reaches AppKit. The system preference "Double-click a window's
 * title bar to" still applies, so the gesture is reproduced here.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.macosTitleBarDoubleClick(
    window: ComposeWindow,
    windowState: WindowState,
    titleBarHeight: Dp = 28.dp,
    trafficLightsWidth: Dp = 80.dp,
): Modifier {
    if (DesktopHostOs.current != DesktopHostOs.MACOS) return this
    if (windowState.placement == WindowPlacement.Fullscreen) return this

    return pointerInput(window, windowState.placement) {
        val titleBarHeightPx = titleBarHeight.toPx()
        val trafficLightsWidthPx = trafficLightsWidth.toPx()
        var previousPress: TitleBarPress? = null

        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.type != PointerEventType.Press) continue
                if (event.button != PointerButton.Primary) continue
                val change = event.changes.firstOrNull() ?: continue
                val press = TitleBarPress(
                    uptimeMs = change.uptimeMillis,
                    position = change.position,
                )
                val isDoubleClick = isTitleBarDoubleClick(
                    previousPress = previousPress,
                    press = press,
                    titleBarHeightPx = titleBarHeightPx,
                    trafficLightsWidthPx = trafficLightsWidthPx,
                    timeoutMs = viewConfiguration.doubleTapTimeoutMillis.toLong(),
                    slopPx = viewConfiguration.touchSlop,
                )
                previousPress = press
                if (!isDoubleClick) continue

                previousPress = null
                change.consume()
                applyMacosTitleBarDoubleClick(window = window, windowState = windowState)
            }
        }
    }
}

internal data class TitleBarPress(
    val uptimeMs: Long,
    val position: Offset,
)

internal fun isTitleBarDoubleClick(
    previousPress: TitleBarPress?,
    press: TitleBarPress,
    titleBarHeightPx: Float,
    trafficLightsWidthPx: Float,
    timeoutMs: Long,
    slopPx: Float,
): Boolean {
    if (press.position.y > titleBarHeightPx) return false
    if (press.position.x < trafficLightsWidthPx) return false

    val previous = previousPress ?: return false
    if (press.uptimeMs - previous.uptimeMs !in 0..timeoutMs) return false
    return (press.position - previous.position).getDistance() <= slopPx
}

internal fun applyMacosTitleBarDoubleClick(
    window: ComposeWindow,
    windowState: WindowState,
) {
    when (macosTitleBarDoubleClickAction) {
        MacosTitleBarDoubleClickAction.Maximize ->
            window.extendedState = toggledZoomExtendedState(window.extendedState)

        MacosTitleBarDoubleClickAction.Minimize ->
            windowState.isMinimized = true

        MacosTitleBarDoubleClickAction.None -> Unit
    }
}

/**
 * AWT maps `MAXIMIZED_BOTH` to AppKit's zoom on macOS: the frame grows to
 * [java.awt.GraphicsEnvironment.getMaximumWindowBounds] and restoring returns to the previous
 * windowed bounds. Other extended-state bits are preserved.
 */
internal fun toggledZoomExtendedState(currentExtendedState: Int): Int =
    if (currentExtendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH) {
        currentExtendedState and Frame.MAXIMIZED_BOTH.inv()
    } else {
        currentExtendedState or Frame.MAXIMIZED_BOTH
    }

internal enum class MacosTitleBarDoubleClickAction { Maximize, Minimize, None }

private val macosTitleBarDoubleClickAction: MacosTitleBarDoubleClickAction by lazy {
    readMacosTitleBarDoubleClickAction()
}

internal fun readMacosTitleBarDoubleClickAction(
    readDefaults: () -> String? = ::readAppleActionOnDoubleClickDefaults,
): MacosTitleBarDoubleClickAction = when (readDefaults()?.trim()?.lowercase()) {
    "minimize" -> MacosTitleBarDoubleClickAction.Minimize
    "none" -> MacosTitleBarDoubleClickAction.None
    else -> MacosTitleBarDoubleClickAction.Maximize
}

private fun readAppleActionOnDoubleClickDefaults(): String? = runCatching {
    val process = ProcessBuilder("defaults", "read", "-g", "AppleActionOnDoubleClick")
        .redirectErrorStream(true)
        .start()
    try {
        if (!process.waitFor(DefaultsTimeoutMs, TimeUnit.MILLISECONDS)) return@runCatching null
        if (process.exitValue() != 0) return@runCatching null
        process.inputStream.bufferedReader().use { it.readText() }
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}.getOrNull()

private const val DefaultsTimeoutMs = 500L
