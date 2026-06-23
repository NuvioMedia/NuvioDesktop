package com.nuvio.app.features.player.desktop

import java.util.Locale

internal enum class DesktopHostOs {
    MACOS,
    WINDOWS,
    LINUX,
    UNKNOWN;

    companion object {
        val current: DesktopHostOs by lazy {
            val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            when {
                osName.contains("mac") -> MACOS
                osName.contains("win") -> WINDOWS
                osName.contains("linux") -> LINUX
                else -> UNKNOWN
            }
        }

        val isWayland: Boolean by lazy {
            if (current != LINUX) return@lazy false
            val sessionType = System.getenv("XDG_SESSION_TYPE").orEmpty().lowercase(Locale.ROOT)
            if (sessionType.contains("wayland")) return@lazy true
            val waylandDisplay = System.getenv("WAYLAND_DISPLAY").orEmpty()
            if (waylandDisplay.isNotBlank()) return@lazy true
            try {
                NativePlayerBridge.isWaylandSession()
            } catch (_: Throwable) {
                false
            }
        }
    }
}
