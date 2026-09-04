package com.nuvio.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.core.diagnostics.SentryInitializer
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.discordrpc.DiscordPresenceManager
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.plugins.configureDesktopQuickJsLibrary
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.desktop.DesktopAppFullscreenController
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.DesktopWindowGeometry
import com.nuvio.app.features.player.desktop.DesktopWindowModeStorage
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.player.desktop.applyNativeDesktopWindowChrome
import com.nuvio.app.features.player.desktop.installDesktopAppFullscreenShortcuts
import com.nuvio.app.features.player.desktop.preloadNativePlayerBridgeAsync
import com.nuvio.app.features.player.desktop.registerDesktopAppFullscreenToggle
import com.nuvio.app.features.player.desktop.trackMaximizedBoundsForCurrentScreen
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.AppIconRepository
import com.nuvio.app.features.settings.applyDesktopRendererPreference
import com.nuvio.app.features.settings.transparentPreviewResource
import java.awt.Desktop
import javax.imageio.ImageIO
import java.awt.Color as AwtColor
import javax.swing.JComponent

private val NuvioDesktopNativeBackground = AwtColor(0x0D, 0x0D, 0x0D)
private const val MacosDarkAquaAppearance = "NSAppearanceNameDarkAqua"

fun main(args: Array<String>) {
    // On Linux, initialize GTK BEFORE AWT/Compose/Skia to prevent GdkDisplayManager
    // type registration conflict (Skiko partially loads GDK without full GTK init).
    if (System.getProperty("os.name", "").lowercase().contains("linux")) {
        runCatching { NativePlayerBridge.initGtkEarly() }
    }
    applyDesktopRendererPreference()
    SentryInitializer.start()
    configureDesktopQuickJsLibrary()
    configureDesktopChrome()
    installDesktopOpenUriHandler()
    handleDesktopLaunchArgs(args)
    preloadNativePlayerBridgeAsync()
    // Load cached profile data synchronously so the profile color is available
    // on the very first Compose frame (matching Android's SharedPreferences behavior).
    ProfileRepository.loadCachedProfiles()
    AppIconRepository.ensureLoaded()
    DiscordPresenceManager.start()

    application {
        val appIconState by AppIconRepository.state.collectAsState()
        val smokePlayerUrl = (
            System.getProperty("nuvio.desktop.smokePlayerUrl")
                ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
            )
            ?.takeIf { it.isNotBlank() }
        val wasFullscreenOnLastExit = remember { DesktopWindowModeStorage.loadWasFullscreen() }
        val wasMaximizedOnLastExit = remember { DesktopWindowModeStorage.loadWasMaximized() }
        val savedGeometry = remember { DesktopWindowModeStorage.loadWindowedGeometry() }
        val restoresMaximizedWindowPlacement = DesktopHostOs.current != DesktopHostOs.MACOS
        val initialPlacement = when {
            wasFullscreenOnLastExit && DesktopHostOs.current != DesktopHostOs.WINDOWS -> {
                WindowPlacement.Fullscreen
            }
            wasMaximizedOnLastExit == false && savedGeometry != null -> {
                WindowPlacement.Floating
            }
            restoresMaximizedWindowPlacement -> {
                WindowPlacement.Maximized
            }
            else -> WindowPlacement.Floating
        }
        val isStartingMaximizedOrFullscreen =
            initialPlacement == WindowPlacement.Maximized || initialPlacement == WindowPlacement.Fullscreen
        val maxScreenBounds = remember {
            runCatching {
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            }.getOrNull()
        }
        val initialWidth = when {
            isStartingMaximizedOrFullscreen && maxScreenBounds != null -> maxScreenBounds.width.dp
            savedGeometry != null -> savedGeometry.width.dp
            else -> 1280.dp
        }
        val initialHeight = when {
            isStartingMaximizedOrFullscreen && maxScreenBounds != null -> maxScreenBounds.height.dp
            savedGeometry != null -> savedGeometry.height.dp
            else -> 820.dp
        }
        val windowState = rememberWindowState(
            width = initialWidth,
            height = initialHeight,
            position = savedGeometry?.let { WindowPosition.Absolute(x = it.x.dp, y = it.y.dp) }
                ?: WindowPosition.PlatformDefault,
            // Windows fullscreen is emulated natively (see DesktopAppFullscreenController)
            // rather than driven by WindowPlacement, so it's restored separately below.
            placement = initialPlacement,
        )
        val fullscreenController = remember { DesktopAppFullscreenController() }

        Window(
            onCloseRequest = {
                P2pStreamingEngine.shutdown()
                DiscordPresenceManager.shutdown()
                SentryInitializer.close()
                exitApplication()
            },
            title = if (smokePlayerUrl == null) "Nuvio" else "Nuvio Player Smoke",
            state = windowState,
            icon = painterResource(appIconState.selected.transparentPreviewResource),
        ) {
            SideEffect {
                window.background = NuvioDesktopNativeBackground
                window.rootPane.background = NuvioDesktopNativeBackground
                window.contentPane.background = NuvioDesktopNativeBackground
                (window.contentPane as? JComponent)?.isOpaque = true
            }
            LaunchedEffect(window, appIconState.selected) {
                val backgroundSuffix = "-transparent"
                val iconPath = "icons/app-icon-${appIconState.selected.key}$backgroundSuffix.png"
                Thread.currentThread().contextClassLoader.getResourceAsStream(iconPath)?.use { stream ->
                    ImageIO.read(stream)?.let { image ->
                        window.iconImages = listOf(image)
                    }
                }
            }

            LaunchedEffect(window) {
                applyNativeDesktopWindowChrome(window)
                installLinuxExtendedMouseButtons()
                // Windows fullscreen is emulated natively and isn't reflected by
                // WindowPlacement, so it must be re-applied once the window peer exists.
                fullscreenController.applyRestoredFullscreenState(window, windowState, wasFullscreenOnLastExit)
            }
            LaunchedEffect(windowState) {
                // Covers OS-driven placement changes too (e.g. the native macOS
                // green-button fullscreen toggle), not just our own shortcuts.
                if (DesktopHostOs.current != DesktopHostOs.WINDOWS) {
                    snapshotFlow { windowState.placement }
                        .collect { placement ->
                            DesktopWindowModeStorage.saveWasFullscreen(placement == WindowPlacement.Fullscreen)
                        }
                }
            }
            LaunchedEffect(windowState) {
                // Only persist geometry while windowed: fullscreen/native-Windows-fullscreen
                // coordinates aren't a meaningful "windowed position" to restore later.
                snapshotFlow { Triple(windowState.placement, windowState.position, windowState.size) }
                    .collect { (placement, position, size) ->
                        val isFullscreen = fullscreenController.isFullscreen(window, windowState)
                        if (!isFullscreen && restoresMaximizedWindowPlacement) {
                            DesktopWindowModeStorage.saveWasMaximized(placement == WindowPlacement.Maximized)
                        }
                        val isWindowed = placement == WindowPlacement.Floating && !isFullscreen
                        if (isWindowed && position.isSpecified) {
                            DesktopWindowModeStorage.saveWindowedGeometry(
                                DesktopWindowGeometry(
                                    x = position.x.value,
                                    y = position.y.value,
                                    width = size.width.value,
                                    height = size.height.value,
                                ),
                            )
                        }
                    }
            }
            DisposableEffect(window, windowState) {
                val unregisterFullscreenToggle = registerDesktopAppFullscreenToggle(
                    handler = { targetWindow ->
                        if (targetWindow == null || targetWindow === window) {
                            fullscreenController.toggle(window, windowState)
                            DesktopWindowModeStorage.saveWasFullscreen(
                                fullscreenController.isFullscreen(window, windowState),
                            )
                        }
                    },
                    isFullscreen = { targetWindow ->
                        (targetWindow == null || targetWindow === window) &&
                            fullscreenController.isFullscreen(window, windowState)
                    },
                )
                val uninstallFullscreenShortcuts = installDesktopAppFullscreenShortcuts(window)
                val untrackMaximizedBounds = window.trackMaximizedBoundsForCurrentScreen()
                onDispose {
                    fullscreenController.dispose(window)
                    uninstallFullscreenShortcuts()
                    untrackMaximizedBounds()
                    unregisterFullscreenToggle()
                }
            }

            if (smokePlayerUrl == null) {
                App()
            } else {
                // The player surface reads LocalNuvioPlatformDensity, which only
                // NuvioTheme provides — the bare smoke harness must supply it too.
                NuvioTheme {
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
}

private fun configureDesktopChrome() {
    if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
        System.setProperty("apple.awt.application.appearance", MacosDarkAquaAppearance)
    }
}

private fun installDesktopOpenUriHandler() {
    if (!Desktop.isDesktopSupported()) return
    val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return
    if (!desktop.isSupported(Desktop.Action.APP_OPEN_URI)) return

    runCatching {
        desktop.setOpenURIHandler { event ->
            event.uri
                ?.toString()
                ?.trim()
                ?.takeIf(::isDesktopAppUrl)
                ?.let(::handleAppUrl)
        }
    }
}

private fun handleDesktopLaunchArgs(args: Array<String>) {
    args.asSequence()
        .map(String::trim)
        .filter(::isDesktopAppUrl)
        .forEach(::handleAppUrl)
}

private fun isDesktopAppUrl(value: String): Boolean =
    value.startsWith("nuvio://", ignoreCase = true) ||
        value.startsWith("stremio://", ignoreCase = true)
