package com.nuvio.app.core.ui

/**
 * Routes the desktop Escape shortcut to the active navigation back handler.
 *
 * Compose Desktop only delivers key events to the focused node, so a root-level
 * [androidx.compose.ui.input.key.onPreviewKeyEvent] cannot observe Escape while a
 * card or list still holds focus. An AWT dispatcher sits in front of the whole
 * dispatch chain and therefore sees every press, which lets Escape behave like
 * the system Back button on every screen, including the player.
 *
 * On top of the app-level handler, [PlatformBackHandler] pushes a modal handler
 * while it is enabled. Escape then closes the active dialog/sheet or leaves the
 * current settings page before it can reach the app-level "navigate back", which
 * used to jump straight to Home from a settings sub-page.
 */
internal object DesktopEscapeShortcut {
    @Volatile
    private var baseHandler: (() -> Unit)? = null

    private val modalHandlers = mutableListOf<() -> Unit>()

    val onBack: (() -> Unit)?
        get() = synchronized(modalHandlers) { modalHandlers.lastOrNull() } ?: baseHandler

    fun register(handler: () -> Unit) {
        baseHandler = handler
    }

    fun unregister(handler: () -> Unit) {
        if (baseHandler === handler) baseHandler = null
    }

    fun push(handler: () -> Unit) {
        synchronized(modalHandlers) {
            modalHandlers.removeAll { it === handler }
            modalHandlers.add(handler)
        }
    }

    fun pop(handler: () -> Unit) {
        synchronized(modalHandlers) {
            modalHandlers.removeAll { it === handler }
        }
    }
}
