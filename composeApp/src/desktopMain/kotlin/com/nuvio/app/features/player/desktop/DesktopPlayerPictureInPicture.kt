package com.nuvio.app.features.player.desktop

import androidx.compose.ui.unit.IntSize
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object DesktopPlayerPictureInPicture {
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    @Volatile
    var isEnabled: Boolean = false
        private set

    private var restoreBounds: Rectangle? = null
    private var restoreAlwaysOnTop: Boolean = false
    private var lastVideoSize: IntSize = IntSize.Zero

    fun toggle() {
        isEnabled = !isEnabled
        applyToCurrentWindow()
        notifyChanged()
    }

    fun update(isPlaying: Boolean, videoSize: IntSize) {
        lastVideoSize = videoSize
        if (!isEnabled) return
        applyToCurrentWindow()
        notifyChanged()
    }

    fun clear() {
        if (isEnabled) {
            isEnabled = false
            applyToCurrentWindow()
            notifyChanged()
        }
    }

    private fun applyToCurrentWindow() {
        val window = currentWindow() ?: return
        if (isEnabled) {
            if (isDesktopAppFullscreen(window)) {
                toggleDesktopAppFullscreen(window)
            }
            enterPictureInPicture(window)
        } else {
            exitPictureInPicture(window)
        }
    }

    private fun enterPictureInPicture(window: Window) {
        if (restoreBounds == null) {
            restoreBounds = Rectangle(window.bounds)
            restoreAlwaysOnTop = window.isAlwaysOnTop
        }

        val bounds = computePictureInPictureBounds(window)
        window.isAlwaysOnTop = true
        window.bounds = bounds
        window.toFront()
        window.requestFocus()
    }

    private fun exitPictureInPicture(window: Window) {
        val bounds = restoreBounds ?: return
        window.isAlwaysOnTop = restoreAlwaysOnTop
        window.bounds = bounds
        restoreBounds = null
    }

    private fun computePictureInPictureBounds(window: Window): Rectangle {
        val graphicsConfiguration = window.graphicsConfiguration
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val screenBounds = graphicsConfiguration.bounds
        val insets = ToolkitInsets.get(graphicsConfiguration)
        val availableWidth = (screenBounds.width - insets.left - insets.right).coerceAtLeast(320)
        val availableHeight = (screenBounds.height - insets.top - insets.bottom).coerceAtLeast(240)
        val aspectRatio = when {
            lastVideoSize.width > 0 && lastVideoSize.height > 0 -> lastVideoSize.width.toFloat() / lastVideoSize.height.toFloat()
            else -> 16f / 9f
        }.coerceIn(1.0f, 2.4f)

        val targetWidth = (availableWidth * 0.34f).toInt().coerceIn(360, 720)
        val targetHeight = (targetWidth / aspectRatio).toInt().coerceIn(220, (availableHeight * 0.5f).toInt().coerceAtLeast(220))
        val width = targetWidth.coerceAtMost(availableWidth)
        val height = targetHeight.coerceAtMost(availableHeight)
        val x = screenBounds.x + screenBounds.width - insets.right - width - 24
        val y = screenBounds.y + screenBounds.height - insets.bottom - height - 24
        return Rectangle(x.coerceAtLeast(screenBounds.x + insets.left), y.coerceAtLeast(screenBounds.y + insets.top), width, height)
    }

    private fun currentWindow(): Window? =
        KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?: Window.getWindows().firstOrNull { it.isDisplayable && it.isVisible }

    private fun notifyChanged() {
        _changes.value += 1
    }
}

private object ToolkitInsets {
    fun get(configuration: java.awt.GraphicsConfiguration): java.awt.Insets {
        val device = configuration.device
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        return runCatching { toolkit.getScreenInsets(configuration) }.getOrElse {
            java.awt.Insets(0, 0, 0, 0)
        }
    }
}