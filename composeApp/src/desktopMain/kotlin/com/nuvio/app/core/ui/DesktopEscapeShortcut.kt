package com.nuvio.app.core.ui

/**
 * Routes the desktop Escape shortcut to the active navigation back handler.
 *
 * Compose Desktop only delivers key events to the focused node, so a root-level
 * [androidx.compose.ui.input.key.onPreviewKeyEvent] cannot observe Escape while a
 * card or list still holds focus. An AWT dispatcher sits in front of the whole
 * dispatch chain and therefore sees every press, which lets Escape behave like
 * the system Back button on every screen, including the player.
 */
internal object DesktopEscapeShortcut {
    @Volatile
    var onBack: (() -> Unit)? = null

    fun register(handler: () -> Unit) {
        onBack = handler
    }

    fun unregister(handler: () -> Unit) {
        if (onBack === handler) onBack = null
    }
}