package com.nuvio.app.features.player.desktop

/**
 * Common contract between [NativePlayerHost] (AWT Canvas, used on macOS/Windows/X11)
 * and [LinuxPlayerHost] (Compose Canvas, used on Linux).
 * Allows [NativePlayerController] to drive both hosts without duplication.
 */
internal interface PlayerHost {
    var nativeHandle: Long

    var onMouseClick: (() -> Unit)?
    var onCursorActivity: (() -> Unit)?

    fun setControlsVisible(visible: Boolean)
    fun noteCursorActivity()
    fun resetCursorVisibility()
    fun dispose() {}
}
