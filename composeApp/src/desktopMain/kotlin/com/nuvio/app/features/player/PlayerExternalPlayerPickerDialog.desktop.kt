package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.nuvio.app.features.settings.ExternalPlayerSelectionDialogContent
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities

/**
 * Desktop implementation that opens a top-level [DialogWindow] which overlays the active
 * player window with a scrim, and centers the picker on top.
 *
 * Why a separate top-level window: the in-app desktop player renders video through a
 * heavyweight AWT/Swing component (libVLC via [androidx.compose.ui.awt.SwingPanel]).
 * Heavyweight Swing canvases are always drawn above lightweight Compose popups/dialogs
 * hosted in the same Compose window. Forcing `alwaysOnTop = true` on a sibling window
 * is the only reliable way to render UI above the video surface.
 *
 * Because `alwaysOnTop = true` makes the dialog ignore the normal window grouping with
 * its owner, we explicitly tie the dialog's visibility to the Nuvio window's state:
 *
 *  - a global [KeyboardFocusManager] listener tracks the currently active window. The
 *    dialog is only shown while the active window is the Nuvio window or one of its
 *    descendants (which includes the dialog itself). The moment focus moves to a foreign
 *    app, the dialog hides.
 *  - a [WindowAdapter] on the Nuvio window hides the dialog while the main window is
 *    iconified and dismisses it if the user closes the app.
 *  - a [ComponentAdapter] keeps the dialog pinned to the Nuvio window's content bounds
 *    as the user moves or resizes the player.
 *
 * The dialog is configured with `focusable = false` so that mouse clicks land on its
 * Compose content without the AWT window stealing keyboard focus from the Nuvio window.
 * That keeps the OS-drawn minimize / maximize / close buttons in the Nuvio title bar
 * fully responsive while the picker is open.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
actual fun PlayerExternalPlayerPickerDialog(
    players: List<ExternalPlayerApp>,
    selectedPlayerId: String?,
    onPlayerSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ownerWindow = remember { findActivePlayerWindow() }
    val initialOverlay = remember(ownerWindow) {
        ownerWindow?.let(::resolveContentBounds) ?: FallbackOverlay
    }
    var overlay by remember { mutableStateOf(initialOverlay) }
    var dialogVisible by remember { mutableStateOf(true) }

    val dialogState = rememberDialogState(
        position = WindowPosition.Absolute(x = initialOverlay.x, y = initialOverlay.y),
        size = DpSize(initialOverlay.width, initialOverlay.height),
    )

    // Track move/resize of the player window so the dialog follows it.
    DisposableEffect(ownerWindow) {
        val window = ownerWindow ?: return@DisposableEffect onDispose {}
        val componentListener = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) {
                resolveContentBounds(window)?.let { overlay = it }
            }

            override fun componentResized(e: ComponentEvent) {
                resolveContentBounds(window)?.let { overlay = it }
            }
        }
        window.addComponentListener(componentListener)
        onDispose { window.removeComponentListener(componentListener) }
    }

    // Hide the dialog with the Nuvio window when it is minimized or being closed.
    DisposableEffect(ownerWindow) {
        val window = ownerWindow ?: return@DisposableEffect onDispose {}
        val windowListener = object : WindowAdapter() {
            override fun windowIconified(e: WindowEvent) {
                dialogVisible = false
            }

            override fun windowDeiconified(e: WindowEvent) {
                if (isActiveWindowInApp(window)) dialogVisible = true
            }

            override fun windowClosing(e: WindowEvent) {
                onDismiss()
            }
        }
        window.addWindowListener(windowListener)
        onDispose { window.removeWindowListener(windowListener) }
    }

    // Globally track which window currently has focus across the whole JVM. The dialog
    // is visible only while the Nuvio window (or one of its descendants such as the
    // dialog itself) is active. When the user Alt-Tabs to a different app the dialog
    // disappears together with the apparent Nuvio chrome.
    DisposableEffect(ownerWindow) {
        val window = ownerWindow ?: return@DisposableEffect onDispose {}
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val listener = PropertyChangeListener { event ->
            if (event.propertyName != "activeWindow") return@PropertyChangeListener
            val active = event.newValue as? Window
            val belongs = active != null && belongsToSameApp(active, window)
            if (!belongs) {
                dialogVisible = false
            } else if (!isWindowIconified(window)) {
                dialogVisible = true
            }
        }
        focusManager.addPropertyChangeListener("activeWindow", listener)
        // Sync initial state.
        dialogVisible = !isWindowIconified(window) && window.isShowing && isActiveWindowInApp(window)
        onDispose { focusManager.removePropertyChangeListener("activeWindow", listener) }
    }

    // Push the dialog state when the bounds change.
    LaunchedEffect(overlay) {
        dialogState.position = WindowPosition.Absolute(x = overlay.x, y = overlay.y)
        dialogState.size = DpSize(overlay.width, overlay.height)
    }

    DialogWindow(
        visible = dialogVisible,
        onCloseRequest = onDismiss,
        state = dialogState,
        title = "",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        // Keep focus on the Nuvio window so the OS title-bar buttons stay responsive
        // and Alt-Tab / focus behavior on the main window is unaffected.
        focusable = false,
    ) {
        val scrimInteractionSource = remember { MutableInteractionSource() }
        val contentInteractionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = scrimInteractionSource,
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.clickable(
                    interactionSource = contentInteractionSource,
                    indication = null,
                    onClick = {},
                ),
            ) {
                ExternalPlayerSelectionDialogContent(
                    players = players,
                    selectedPlayerId = selectedPlayerId,
                    onPlayerSelected = onPlayerSelected,
                    onDismiss = onDismiss,
                    modifier = Modifier,
                )
            }
        }
    }
}

/**
 * Content-area bounds of the player window, expressed in Compose-style density-independent
 * pixels (dp). On modern Java/Compose Desktop the AWT window coordinates are already in
 * the system's logical (DPI-aware) pixel space, which matches Compose dp 1:1, so we can
 * pass them straight through.
 */
private data class PlayerOverlayBounds(
    val x: Dp,
    val y: Dp,
    val width: Dp,
    val height: Dp,
)

/** Used when no parent window is available (should not happen on desktop). */
private val FallbackOverlay = PlayerOverlayBounds(
    x = 0.dp,
    y = 0.dp,
    width = 440.dp,
    height = 360.dp,
)

private fun resolveContentBounds(window: Window): PlayerOverlayBounds? {
    if (!window.isShowing) return null
    val insets = window.insets
    val x = window.x + insets.left
    val y = window.y + insets.top
    val width = (window.width - insets.left - insets.right).coerceAtLeast(0)
    val height = (window.height - insets.top - insets.bottom).coerceAtLeast(0)
    if (width == 0 || height == 0) return null
    return PlayerOverlayBounds(
        x = x.dp,
        y = y.dp,
        width = width.dp,
        height = height.dp,
    )
}

private fun findActivePlayerWindow(): Window? {
    val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    focusManager.activeWindow?.takeIf { it.isShowing }?.let { return it }
    focusManager.focusedWindow?.takeIf { it.isShowing }?.let { return it }
    return SwingUtilities.getWindowAncestor(focusManager.focusOwner)
}

/**
 * Returns `true` if [candidate] is [owner] itself or a window owned (directly or
 * transitively) by [owner]. Used to recognise that our own overlay dialog and any
 * other child popups of the Nuvio window should not be treated as "foreign apps".
 */
private fun belongsToSameApp(candidate: Window, owner: Window): Boolean {
    var current: Window? = candidate
    while (current != null) {
        if (current === owner) return true
        current = current.owner
    }
    return false
}

private fun isActiveWindowInApp(owner: Window): Boolean {
    val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow ?: return false
    return belongsToSameApp(active, owner)
}

private fun isWindowIconified(window: Window): Boolean =
    (window as? Frame)?.let { it.state and Frame.ICONIFIED != 0 } ?: false
