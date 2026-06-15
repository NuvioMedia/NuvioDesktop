package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import com.nuvio.app.LocalDesktopWindow
import com.nuvio.app.desktop.DesktopBorderlessFullscreenController
import java.awt.Cursor
import java.awt.AWTEvent
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.image.BufferedImage
import kotlinx.coroutines.delay

@Composable
actual fun ManagePlayerCursorVisibility(visible: Boolean) {
    val window = LocalDesktopWindow.current
    val hiddenCursor = remember { createHiddenPlayerCursor() }

    DisposableEffect(window) {
        val previousCursor = window?.cursor
        onDispose {
            if (window != null && previousCursor != null) {
                window.cursor = previousCursor
            }
        }
    }

    SideEffect {
        window?.cursor = if (visible) Cursor.getDefaultCursor() else hiddenCursor
    }
}

@Composable
actual fun rememberPlayerFullscreenController(): PlayerFullscreenController {
    val window = LocalDesktopWindow.current as? ComposeWindow
    val fullscreenRevision = DesktopBorderlessFullscreenController.revision
    var isFullscreen by remember(window, fullscreenRevision) {
        mutableStateOf(window?.isPlayerFullscreen() == true)
    }

    LaunchedEffect(window) {
        while (true) {
            isFullscreen = window?.isPlayerFullscreen() == true
            delay(250)
        }
    }

    return object : PlayerFullscreenController {
        override val isFullscreenSupported: Boolean
            get() = window != null

        override val isFullscreen: Boolean
            get() = isFullscreen

        override fun toggleFullscreen() {
            val w = window ?: return
            w.toggleDesktopFullscreen()
            isFullscreen = w.isPlayerFullscreen()
        }
    }
}

@Composable
actual fun ManageFullscreenKeyboardShortcuts(
    isHomeRouteActive: Boolean,
    onBack: () -> Unit,
) {
    val window = LocalDesktopWindow.current as? ComposeWindow
    val currentOnBack by rememberUpdatedState(onBack)
    val currentIsHomeRouteActive by rememberUpdatedState(isHomeRouteActive)

    DisposableEffect(window) {
        val composeWindow = window ?: return@DisposableEffect onDispose {}
        val keyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { event ->
            if (event.keyCode == KeyEvent.VK_ESCAPE && event.id == KeyEvent.KEY_PRESSED) {
                val action = KeybindsStorage.actionForKeyCode(event.keyCode, event.modifiersEx)
                if (action == "exit_fullscreen") {
                    if (composeWindow.isPlayerFullscreen()) {
                        composeWindow.exitDesktopFullscreen()
                    } else if (!currentIsHomeRouteActive) {
                        currentOnBack()
                    }
                    return@KeyEventDispatcher true
                }
            }
            if (event.id != KeyEvent.KEY_RELEASED) {
                return@KeyEventDispatcher false
            }
            if (event.keyCode == KeyEvent.VK_ESCAPE) {
                return@KeyEventDispatcher false
            }
            if (event.keyCode == KeyEvent.VK_TAB &&
                event.modifiersEx and KeyEvent.ALT_DOWN_MASK != 0
            ) {
                return@KeyEventDispatcher false
            }
            when (KeybindsStorage.actionForKeyCode(event.keyCode, event.modifiersEx)) {
                "toggle_app_fullscreen" -> {
                    composeWindow.toggleDesktopFullscreen()
                    true
                }
                "exit_fullscreen" -> {
                    if (composeWindow.isPlayerFullscreen()) {
                        composeWindow.exitDesktopFullscreen()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        keyboardFocusManager.addKeyEventDispatcher(dispatcher)

        val mouseListener = AWTEventListener { awtEvent ->
            if (awtEvent.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
            val mouseEvent = awtEvent as? MouseEvent ?: return@AWTEventListener
            val isSideButton = mouseEvent.button in 4..9
            if (!isSideButton) return@AWTEventListener
            if (!composeWindow.isFocused) return@AWTEventListener
            if (composeWindow.isPlayerFullscreen()) {
                composeWindow.exitDesktopFullscreen()
            } else if (!currentIsHomeRouteActive) {
                currentOnBack()
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(
            mouseListener,
            AWTEvent.MOUSE_EVENT_MASK,
        )

        val focusListener = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {
                composeWindow.repaint()
                Toolkit.getDefaultToolkit().sync()
            }
            override fun windowLostFocus(e: WindowEvent?) = Unit
        }
        composeWindow.addWindowFocusListener(focusListener)

        onDispose {
            keyboardFocusManager.removeKeyEventDispatcher(dispatcher)
            composeWindow.removeWindowFocusListener(focusListener)
            Toolkit.getDefaultToolkit().removeAWTEventListener(mouseListener)
        }
    }
}

@Composable
actual fun BindPlayerKeyboardShortcuts(
    enabled: Boolean,
    handlers: PlayerKeyboardShortcutHandlers,
) {
    val latestHandlers by rememberUpdatedState(handlers)

    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val keyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_RELEASED) {
                return@KeyEventDispatcher false
            }
            if (event.keyCode == KeyEvent.VK_TAB &&
                event.modifiersEx and KeyEvent.ALT_DOWN_MASK != 0
            ) {
                return@KeyEventDispatcher false
            }
            when (KeybindsStorage.actionForKeyCode(event.keyCode, event.modifiersEx)) {
                "toggle_fullscreen" -> latestHandlers.toggleFullscreen()
                "play_pause" -> latestHandlers.togglePlayback()
                "seek_forward_10s" -> latestHandlers.seekForward()
                "seek_backward_10s" -> latestHandlers.seekBackward()
                "volume_up" -> latestHandlers.volumeUp()
                "volume_down" -> latestHandlers.volumeDown()
                "mute" -> latestHandlers.toggleMute()
                "cycle_speed" -> latestHandlers.cyclePlaybackSpeed()
                "next_episode" -> latestHandlers.playNextEpisode()
                "skip_intro" -> latestHandlers.skipActiveSegment()
                else -> return@KeyEventDispatcher false
            }
            true
        }

        keyboardFocusManager.addKeyEventDispatcher(dispatcher)
        onDispose {
            keyboardFocusManager.removeKeyEventDispatcher(dispatcher)
        }
    }
}

private fun ComposeWindow.toggleDesktopFullscreen() {
    DesktopBorderlessFullscreenController.toggle(this)
}

private fun ComposeWindow.exitDesktopFullscreen() {
    DesktopBorderlessFullscreenController.exit(this)
}

private fun ComposeWindow.isPlayerFullscreen(): Boolean {
    return DesktopBorderlessFullscreenController.isFullscreen(this)
}

private fun createHiddenPlayerCursor(): Cursor {
    val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    return Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "nuvio-player-hidden-cursor")
}

actual val usesNativePlayerChrome: Boolean = false

actual val usesAnimatedPlayerChrome: Boolean = false
