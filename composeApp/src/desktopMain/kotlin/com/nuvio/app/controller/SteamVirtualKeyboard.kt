package com.nuvio.app.controller

import java.awt.Desktop
import java.net.URI

/**
 * Summons Steam's Big Picture / SteamOS on-screen keyboard via the `steam://` protocol when
 * Nuvio is running under Steam or gamescope and the user enables it in Controller settings.
 */
internal object SteamVirtualKeyboard : PlatformVirtualKeyboard.Delegate {
    @Volatile
    private var active = false

    override val isKeyboardActive: Boolean
        get() = active

    fun install() {
        if (SteamEnvironment.steamKeyboardAvailable) {
            PlatformVirtualKeyboard.delegate = this
        }
    }

    fun uninstall() {
        if (PlatformVirtualKeyboard.delegate === this) {
            PlatformVirtualKeyboard.delegate = null
        }
        active = false
    }

    override fun show(fromAutoFocus: Boolean) {
        if (!SteamEnvironment.steamKeyboardAvailable) return
        if (!ControllerSettings.steamKeyboardEnabled.value) return
        if (openSteamUri("steam://open/keyboard")) {
            active = true
        }
    }

    override fun hide() {
        if (!active) return
        active = false
        if (SteamEnvironment.steamKeyboardAvailable) {
            openSteamUri("steam://close/keyboard")
        }
    }

    private fun openSteamUri(uri: String): Boolean {
        val desktopOpened = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(uri))
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (desktopOpened) return true

        return runCatching {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val command = when {
                "linux" in os -> listOf("xdg-open", uri)
                "mac" in os -> listOf("open", uri)
                "win" in os -> listOf("cmd", "/c", "start", "", uri)
                else -> return@runCatching false
            }
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            true
        }.getOrDefault(false)
    }
}
