package com.nuvio.app.features.player

import java.awt.Desktop
import java.io.File
import java.net.URI
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.WindowPosition

private data class DesktopExternalPlayerIntent(
    val request: ExternalPlayerPlaybackRequest,
    val playerId: String?,
)

internal actual object ExternalPlayerPlatform {
    private const val systemPlayerId = "system"

    private val isWindows = System.getProperty("os.name").contains("win", ignoreCase = true)

    private val isMac = System.getProperty("os.name").contains("mac", ignoreCase = true)

    private val windowsPlayers = listOf(
        ExternalPlayerApp("vlc", "VLC media player"),
        ExternalPlayerApp("mpc_hc", "MPC-HC"),
        ExternalPlayerApp("mpc_be", "MPC-BE"),
        ExternalPlayerApp("potplayer", "PotPlayer"),
        ExternalPlayerApp("mpv", "mpv"),
        ExternalPlayerApp("kmp", "KMPlayer"),
        ExternalPlayerApp("gom", "GOM Player"),
        ExternalPlayerApp("kodi", "Kodi"),
        ExternalPlayerApp("zoomplayer", "Zoom Player"),
        ExternalPlayerApp("bsplayer", "BS.Player"),
    )

    private val macPlayers = listOf(
        ExternalPlayerApp("vlc", "VLC media player"),
        ExternalPlayerApp("elmedia", "Elmedia Player"),
        ExternalPlayerApp("iina", "IINA"),
        ExternalPlayerApp("mpv", "mpv"),
    )

    private fun getWindowsExecutable(id: String): String? {
        val programFiles = System.getenv("ProgramW6432") ?: System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
        val appData = System.getenv("APPDATA") ?: System.getenv("USERPROFILE")?.let { "$it\\AppData\\Roaming" }
        return when (id) {
            "vlc" -> listOf(
                "$programFiles\\VideoLAN\\VLC\\vlc.exe",
                "$programFilesX86\\VideoLAN\\VLC\\vlc.exe"
            ).firstOrNull { File(it).exists() }
            "mpc_hc" -> listOf(
                "$programFiles\\MPC-HC\\mpc-hc64.exe",
                "$programFiles\\MPC-HC\\mpc-hc.exe",
                "$programFilesX86\\MPC-HC\\mpc-hc.exe"
            ).firstOrNull { File(it).exists() }
            "mpc_be" -> listOf(
                "$programFiles\\MPC-BE x64\\mpc-be64.exe",
                "$programFiles\\MPC-BE\\mpc-be.exe",
                "$programFilesX86\\MPC-BE\\mpc-be.exe"
            ).firstOrNull { File(it).exists() }
            "potplayer" -> listOf(
                "$programFiles\\DAUM\\PotPlayer\\PotPlayer64.exe",
                "$programFilesX86\\DAUM\\PotPlayer\\PotPlayer.exe",
                "$programFiles\\DAUM\\PotPlayer\\PotPlayer.exe"
            ).firstOrNull { File(it).exists() }
            "mpv" -> listOf(
                "$programFiles\\mpv\\mpv.exe",
                "$programFilesX86\\mpv\\mpv.exe",
                "$appData\\mpv\\mpv.exe",
                System.getenv("USERPROFILE")?.let { "$it\\scoop\\apps\\mpv\\current\\mpv.exe" },
                System.getenv("USERPROFILE")?.let { "$it\\AppData\\Local\\Microsoft\\WinGet\\Packages\\mpv.mpv_Microsoft.Winget.Source_8wekyb3d8bbwe\\mpv.exe" }
            ).filterNotNull().firstOrNull { File(it).exists() }
            "kmp" -> listOf(
                "$programFiles\\KMPlayer\\KMPlayer.exe",
                "$programFilesX86\\KMPlayer\\KMPlayer.exe",
                "$programFiles\\KMPlayer 64X\\KMPlayer64.exe",
                "$programFilesX86\\KMPlayer 64X\\KMPlayer64.exe",
                "$programFiles\\KMPlayer 64X\\KMP64.exe",
                "$programFilesX86\\KMPlayer 64X\\KMP64.exe"
            ).firstOrNull { File(it).exists() }
            "gom" -> listOf(
                "$programFiles\\GRETECH\\GomPlayer\\GOM.exe",
                "$programFilesX86\\GRETECH\\GomPlayer\\GOM.exe",
                "$programFiles\\GOM\\GomPlayer\\GOM.exe",
                "$programFilesX86\\GOM\\GomPlayer\\GOM.exe",
                "$programFiles\\GOM\\GOMPlayer\\GOM.exe",
                "$programFilesX86\\GOM\\GOMPlayer\\GOM.exe"
            ).firstOrNull { File(it).exists() }
            "kodi" -> listOf(
                "$programFiles\\Kodi\\kodi.exe",
                "$programFilesX86\\Kodi\\kodi.exe"
            ).firstOrNull { File(it).exists() }
            "zoomplayer" -> listOf(
                "$programFiles\\Zoom Player\\zplayer.exe",
                "$programFilesX86\\Zoom Player\\zplayer.exe"
            ).firstOrNull { File(it).exists() }
            "bsplayer" -> listOf(
                "$programFiles\\Webteh\\BSPlayer\\bsplayer.exe",
                "$programFilesX86\\Webteh\\BSPlayer\\bsplayer.exe"
            ).firstOrNull { File(it).exists() }
            else -> null
        }
    }

    private fun getMacAppPath(id: String): String? {
        val home = System.getProperty("user.home")
        val appName = when (id) {
            "vlc" -> "VLC.app"
            "elmedia" -> "Elmedia Player.app"
            "iina" -> "IINA.app"
            "mpv" -> "mpv.app"
            else -> return null
        }
        return listOf(
            "/Applications/$appName",
            "$home/Applications/$appName"
        ).firstOrNull { File(it).exists() }
    }

    actual fun defaultPlayerId(): String? {
        if (isWindows) {
            val installed = windowsPlayers.firstOrNull { getWindowsExecutable(it.id) != null }
            if (installed != null) return installed.id
        } else if (isMac) {
            val installed = macPlayers.firstOrNull { getMacAppPath(it.id) != null }
            if (installed != null) return installed.id
        }
        return systemPlayerId
    }

    actual fun availablePlayers(): List<ExternalPlayerApp> {
        val players = mutableListOf(ExternalPlayerApp(systemPlayerId, "System default"))
        if (isWindows) {
            players.addAll(windowsPlayers.filter { getWindowsExecutable(it.id) != null })
        } else if (isMac) {
            players.addAll(macPlayers.filter { getMacAppPath(it.id) != null })
        }
        return players
    }

    actual fun open(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerOpenResult {
        if (playerId != null && playerId != systemPlayerId) {
            val opened = if (isWindows) {
                val exePath = getWindowsExecutable(playerId)
                if (exePath != null) {
                    runCatching { ProcessBuilder(exePath, request.sourceUrl).start() }.isSuccess
                } else false
            } else if (isMac) {
                val appPath = getMacAppPath(playerId)
                if (appPath != null) {
                    runCatching { ProcessBuilder("open", "-a", appPath, request.sourceUrl).start() }.isSuccess
                } else false
            } else false

            if (opened) return ExternalPlayerOpenResult.Opened
        }

        return if (openUri(request.sourceUrl)) {
            ExternalPlayerOpenResult.Opened
        } else {
            ExternalPlayerOpenResult.Failed
        }
    }

    actual fun buildIntent(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerIntentResult =
        ExternalPlayerIntentResult.Success(DesktopExternalPlayerIntent(request, playerId))

    internal fun launch(intent: Any): Boolean {
        val desktopIntent = intent as? DesktopExternalPlayerIntent ?: return false
        return open(desktopIntent.request, desktopIntent.playerId) == ExternalPlayerOpenResult.Opened
    }

    private fun openUri(rawUri: String): Boolean {
        val uri = runCatching { URI(rawUri) }.getOrNull()
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()

        if (uri != null && desktop != null && Desktop.isDesktopSupported()) {
            val opened = runCatching {
                if (uri.scheme.equals("file", ignoreCase = true)) {
                    desktop.open(File(uri))
                } else {
                    desktop.browse(uri)
                }
            }.isSuccess
            if (opened) return true
        }

        return openWithPlatformCommand(rawUri)
    }

    private fun openWithPlatformCommand(rawUri: String): Boolean {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            osName.contains("mac") -> listOf("open", rawUri)
            osName.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", rawUri)
            else -> listOf("xdg-open", rawUri)
        }
        return runCatching { ProcessBuilder(command).start() }.isSuccess
    }
}

