package com.nuvio.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.nuvio.app.desktop.DesktopBorderlessFullscreenController
import com.nuvio.app.desktop.DesktopPlayerRegistry
import com.nuvio.app.desktop.DesktopRuntimeLog
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.desktop.applyNativeDesktopWindowChrome
import com.nuvio.app.features.player.desktop.installDesktopAppFullscreenShortcuts
import com.nuvio.app.features.player.desktop.preloadNativePlayerBridgeAsync
import com.nuvio.app.features.player.desktop.registerDesktopAppFullscreenToggle
import java.awt.Color as AwtColor
import javax.swing.JComponent
import kotlin.system.exitProcess

private val NuvioDesktopNativeBackground = AwtColor(0x0D, 0x0D, 0x0D)
private const val NuvioDesktopIconPath = "icons/nuvio-app-icon.png"
private const val MacosDarkAquaAppearance = "NSAppearanceNameDarkAqua"

fun main() {
    DesktopRuntimeLog.initialize(enabled = true)
    configureDesktopChrome()
    preloadNativePlayerBridgeAsync()

    application {
        val smokePlayerUrl = (
            System.getProperty("nuvio.desktop.smokePlayerUrl")
                ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
            )
            ?.takeIf { it.isNotBlank() }
        val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)

        Window(
            onCloseRequest = {
                DesktopPlayerRegistry.releaseAll("windowClose")
                DesktopPlayerRegistry.closeAll("windowClose")
                DesktopPlayerRegistry.awaitAllCloses(timeoutMs = 1500L)
                exitApplication()
                exitProcess(0)
            },
            title = if (smokePlayerUrl == null) "Nuvio" else "Nuvio Player Smoke",
            state = windowState,
            icon = painterResource(NuvioDesktopIconPath),
        ) {
            SideEffect {
                window.background = NuvioDesktopNativeBackground
                window.rootPane.background = NuvioDesktopNativeBackground
                window.contentPane.background = NuvioDesktopNativeBackground
                (window.contentPane as? JComponent)?.isOpaque = true
            }
            LaunchedEffect(window) {
                applyNativeDesktopWindowChrome(window)
            }
            DisposableEffect(window, windowState) {
                val unregisterFullscreenToggle = registerDesktopAppFullscreenToggle { targetWindow ->
                    if (targetWindow != null && targetWindow !== window) return@registerDesktopAppFullscreenToggle
                    DesktopBorderlessFullscreenController.toggle(window)
                }
                val uninstallFullscreenShortcuts = installDesktopAppFullscreenShortcuts(window)
                onDispose {
                    uninstallFullscreenShortcuts()
                    unregisterFullscreenToggle()
                }
            }

            if (smokePlayerUrl == null) {
                CompositionLocalProvider(LocalDesktopWindow provides window) {
                    App()
                }
            } else {
                PlatformPlayerSurface(
                    sourceUrl = smokePlayerUrl,
                    modifier = Modifier.fillMaxSize(),
                    onControllerReady = {},
                    onSnapshot = {},
                    onError = {},
                )
            }
        }
    }
}

private fun configureDesktopChrome() {
    if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
        System.setProperty("apple.awt.application.appearance", MacosDarkAquaAppearance)
    }
}
