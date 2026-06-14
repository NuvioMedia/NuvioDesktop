package com.nuvio.app.desktop

import java.awt.EventQueue

internal object DesktopExternalPlaybackWindowController {
    @Volatile
    private var callbacks: Callbacks? = null

    fun register(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun clear(callbacks: Callbacks) {
        if (this.callbacks === callbacks) {
            this.callbacks = null
        }
    }

    fun minimizeToTray(playerId: String, processPid: Long?) {
        DesktopRuntimeLog.info(
            "externalPlayer window minimize requested playerId=$playerId processPid=${processPid ?: "unknown"}",
        )
        val currentCallbacks = callbacks
        if (currentCallbacks == null) {
            DesktopRuntimeLog.warn("externalPlayer window minimize skipped: controller not registered")
            return
        }
        EventQueue.invokeLater {
            currentCallbacks.minimizeToTray(playerId)
        }
    }

    fun restoreFromTray(reason: String) {
        DesktopRuntimeLog.info("externalPlayer tray restore requested reason=$reason")
        val currentCallbacks = callbacks ?: return
        EventQueue.invokeLater {
            currentCallbacks.restoreFromTray(reason)
        }
    }

    data class Callbacks(
        val minimizeToTray: (playerId: String) -> Unit,
        val restoreFromTray: (reason: String) -> Unit,
    )
}
