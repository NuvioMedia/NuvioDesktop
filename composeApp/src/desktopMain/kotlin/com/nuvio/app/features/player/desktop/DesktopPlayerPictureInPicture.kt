package com.nuvio.app.features.player.desktop

import androidx.compose.ui.unit.IntSize
import co.touchlab.kermit.Logger
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Window
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DesktopPlayerPipLabels(
    val windowTitle: String,
    val restoreTooltip: String,
    val closeTooltip: String,
)

/** Coordinates the independent PiP window without moving the Compose/AWT Canvas. */
internal object DesktopPlayerPictureInPicture {
    private val log = Logger.withTag("DesktopPlayerPiP")
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    @Volatile
    var isEnabled: Boolean = false
        private set

    private var host: NativePlayerHost? = null
    private var controller: NativePlayerController? = null
    private var pipWindow: DesktopPlayerPipWindow? = null
    private var labels = DesktopPlayerPipLabels("", "", "")
    private var lastVideoSize = IntSize.Zero
    private var transition = false
    private var lastToggleAtMs = 0L

    fun setHost(value: NativePlayerHost) = onEdt {
        host = value
    }

    fun setController(value: NativePlayerController) = onEdt {
        controller = value
    }

    fun setLabels(newLabels: DesktopPlayerPipLabels) = onEdt {
        labels = newLabels
        pipWindow?.updateLocalizedLabels(
            windowTitle = newLabels.windowTitle,
            restoreTooltip = newLabels.restoreTooltip,
            closeTooltip = newLabels.closeTooltip,
        )
    }

    fun update(isPlaying: Boolean, videoSize: IntSize) = onEdt {
        lastVideoSize = videoSize
        pipWindow?.let { window ->
            if (videoSize.width > 0 && videoSize.height > 0) {
                window.aspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                window.revalidate()
            }
        }
    }

    fun toggle() = onEdt {
        if (transition) return@onEdt
        val now = System.currentTimeMillis()
        if (now - lastToggleAtMs < 300L) return@onEdt
        lastToggleAtMs = now
        log.d { "toggle enabled=$isEnabled host=${host != null} controller=${controller != null}" }
        if (isEnabled) restoreOnEdt() else enterOnEdt()
    }

    fun clear() = onEdt {
        if (transition || !isEnabled) return@onEdt
        restoreOnEdt()
    }

    fun release() = onEdt {
        transition = true
        val window = pipWindow
        pipWindow = null
        isEnabled = false
        window?.dispose()
        host = null
        controller = null
        transition = false
        notifyChanged()
    }

    private fun enterOnEdt() {
        val mainHost = host ?: return
        val player = controller ?: return
        if (!mainHost.isDisplayable) return

        transition = true
        val owner = currentWindow() ?: SwingUtilities.getWindowAncestor(mainHost)
        val window = DesktopPlayerPipWindow(
            ownerWindow = null,
            onRestoreRequested = ::clear,
            onCloseRequested = ::clear,
        ).apply {
            aspectRatio = videoAspectRatio()
            updateLocalizedLabels(labels.windowTitle, labels.restoreTooltip, labels.closeTooltip)
            bounds = computeDefaultBounds(owner, aspectRatio)
            isAlwaysOnTop = true
            isVisible = true
        }
        pipWindow = window

        runCatching {
            val windowPointer = AwtNativeViewResolver.resolveNativeViewPointer(window)
            NativePlayerBridge.setWindowResizable(windowPointer, true)
        }.onFailure { error -> log.w(error) { "failed to enable PiP native resize" } }

        val pipHost = window.videoHolderPanel
        log.d { "created window displayable=${window.isDisplayable} visible=${window.isVisible} pipHostDisplayable=${pipHost.isDisplayable}" }
        if (!pipHost.isDisplayable) {
            window.dispose()
            pipWindow = null
            transition = false
            return
        }
        if (!player.reparentSurface(pipHost)) {
            log.w { "native surface reparent failed; closing PiP window" }
            window.dispose()
            pipWindow = null
            transition = false
            return
        }
        isEnabled = true
        log.d { "PiP entered" }
        transition = false
        notifyChanged()
        window.toFront()
        window.requestFocus()
    }

    private fun restoreOnEdt() {
        val mainHost = host ?: return
        val player = controller ?: return
        val window = pipWindow ?: return
        if (!mainHost.isDisplayable) return

        transition = true
        val restored = player.reparentSurface(mainHost)
        log.d { "restoring PiP native surface success=$restored" }
        window.isVisible = false
        window.dispose()
        pipWindow = null
        isEnabled = false
        transition = false
        notifyChanged()
        mainHost.requestFocusInWindow()
    }

    private fun videoAspectRatio(): Float =
        if (lastVideoSize.width > 0 && lastVideoSize.height > 0) {
            lastVideoSize.width.toFloat() / lastVideoSize.height.toFloat()
        } else {
            16f / 9f
        }

    private fun computeDefaultBounds(window: Window?, aspectRatio: Float): Rectangle {
        val configuration = window?.graphicsConfiguration
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val screen = configuration.bounds
        val insets = ToolkitInsets.get(configuration)
        val width = 480
        val height = (width / aspectRatio).toInt()
        return Rectangle(
            screen.x + screen.width - insets.right - width - 24,
            screen.y + screen.height - insets.bottom - height - 24,
            width,
            height,
        )
    }

    private fun currentWindow(): Window? =
        KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?: Window.getWindows().firstOrNull { it.isDisplayable && it.isVisible }

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
    }

    private fun notifyChanged() {
        _changes.value += 1
    }
}

private object ToolkitInsets {
    fun get(configuration: java.awt.GraphicsConfiguration): java.awt.Insets =
        runCatching { java.awt.Toolkit.getDefaultToolkit().getScreenInsets(configuration) }
            .getOrElse { java.awt.Insets(0, 0, 0, 0) }
}
