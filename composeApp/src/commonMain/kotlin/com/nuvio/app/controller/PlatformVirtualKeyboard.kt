/*
 * Ported from NuvioDeck (https://github.com/kllakm/NuvioDeck), GPLv3.
 */
package com.nuvio.app.controller

/**
 * Platform hook for an on-screen keyboard.
 *
 * The desktop build has no reliable cross-platform OSK integration, so this default implementation
 * only tracks state. A platform may replace [delegate] with a real keyboard later.
 */
object PlatformVirtualKeyboard {
    interface Delegate {
        fun show(fromAutoFocus: Boolean)
        fun hide()
        val isKeyboardActive: Boolean
    }

    @Volatile
    var delegate: Delegate? = null

    var isKeyboardActive: Boolean
        get() = delegate?.isKeyboardActive ?: false
        set(value) { /* state is owned by the delegate */ }

    fun show(fromAutoFocus: Boolean = false) {
        delegate?.show(fromAutoFocus)
    }

    fun hide() {
        delegate?.hide()
    }

    fun registerInputFocusRestorer(restorer: (() -> Unit)?) {
        // No OSK on this platform yet; the restorer is unused.
    }
}
