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
    /** Routes Escape through Compose's NavigationBackHandler ordering (screens, dialogs, NavDisplay). */
    @Volatile
    var composeBack: (() -> Boolean)? = null

    @Volatile
    private var fallback: (() -> Unit)? = null

    fun setFallback(handler: () -> Unit) {
        fallback = handler
    }

    fun clearFallback(handler: () -> Unit) {
        if (fallback === handler) fallback = null
    }

    fun dispatch(): Boolean {
        if (composeBack?.invoke() == true) return true
        val appFallback = fallback ?: return false
        appFallback()
        return true
    }
}