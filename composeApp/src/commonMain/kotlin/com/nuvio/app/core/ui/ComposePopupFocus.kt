package com.nuvio.app.core.ui

import androidx.compose.ui.focus.FocusManager

/**
 * Tracks the [FocusManager] of the popup composition that currently owns the screen.
 *
 * Compose popups and modal sheets have their own focus owner; moving focus through the root
 * manager does nothing inside them. While a popup is visible its manager is on top of this stack
 * so the desktop key/gamepad bridge moves focus inside the popup (and Compose scrolls its lists).
 */
internal object ComposePopupFocus {
    private val managers = mutableListOf<FocusManager>()

    val active: FocusManager?
        get() = synchronized(managers) { managers.lastOrNull() }

    fun push(manager: FocusManager) {
        synchronized(managers) {
            managers.removeAll { it === manager }
            managers.add(manager)
        }
    }

    fun pop(manager: FocusManager) {
        synchronized(managers) { managers.removeAll { it === manager } }
    }
}
