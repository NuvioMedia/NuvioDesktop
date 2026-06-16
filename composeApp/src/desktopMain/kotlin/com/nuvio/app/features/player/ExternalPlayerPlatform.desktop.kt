package com.nuvio.app.features.player

import com.nuvio.app.desktop.DesktopRuntimeLog
import com.nuvio.app.desktop.DesktopExternalPlaybackWindowController
import java.io.File
import java.lang.ProcessBuilder.Redirect

internal actual object ExternalPlayerPlatform {
    private val osName: String by lazy {
        System.getProperty("os.name")?.lowercase().orEmpty()
    }

    private val isWindows: Boolean by lazy { osName.contains("windows") }
    private val isMacos: Boolean by lazy { osName.contains("mac") }

    private val allDefinitions: List<DesktopPlayerDefinition> by lazy {
        when {
            isWindows -> windowsDesktopPlayerDefinitions
            isMacos -> macosDesktopPlayerDefinitions
            else -> linuxDesktopPlayerDefinitions
        }
    }

    private val detectedPlayers: List<DesktopPlayerInstall> by lazy {
        val players = when {
            isWindows -> detectWindowsExternalPlayers().map { it.toDesktopPlayerInstall() }
            isMacos -> detectMacosExternalPlayers().map { it.toDesktopPlayerInstall() }
            else -> detectLinuxExternalPlayers().map { it.toDesktopPlayerInstall() }
        }
        DesktopRuntimeLog.info(
            "externalPlayer detection complete count=${players.size} ids=${players.joinToString { it.definition.id }}",
        )
        players
    }

    actual fun defaultPlayerId(): String? =
        detectedPlayers.firstOrNull()?.definition?.id

    actual fun availablePlayers(): List<ExternalPlayerApp> =
        detectedPlayers.map { install ->
            ExternalPlayerApp(
                id = install.definition.id,
                name = install.definition.name,
            )
        }

    actual fun open(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerOpenResult {
        DesktopRuntimeLog.info(
            "externalPlayer open requested configuredId=${playerId ?: "none"} " +
                "sourceKind=${request.sourceUrl.toExternalSourceKind()} " +
                "sourceKey=${request.sourceUrl.stableExternalLogKey()} " +
                "headers=${request.sourceHeaders.keys.sorted()} " +
                "resumePositionMs=${request.resumePositionMs.coerceAtLeast(0L)}",
        )
        if (playerId.isNullOrBlank()) {
            DesktopRuntimeLog.warn("externalPlayer open rejected: no configured player")
            return ExternalPlayerOpenResult.NotConfigured
        }

        if (playerId.startsWith(CUSTOM_PLAYER_PREFIX)) {
            return openCustomPlayer(request, playerId)
        }

        val knownDefinition = allDefinitions.firstOrNull { it.id == playerId }
            ?: run {
                DesktopRuntimeLog.warn("externalPlayer open rejected: unknown configured id=$playerId")
                return ExternalPlayerOpenResult.NotConfigured
            }
        val install = detectedPlayers.firstOrNull { it.definition.id == playerId }
            ?: run {
                DesktopRuntimeLog.warn("External player unavailable id=${knownDefinition.id}")
                return ExternalPlayerOpenResult.NoPlayerAvailable
            }
        val commandResult = buildDesktopPlayerCommand(install, request)
        val command = commandResult.command
            ?: run {
                DesktopRuntimeLog.warn(
                    "External player launch rejected id=${install.definition.id} reason=${commandResult.failureReason}",
                )
                return ExternalPlayerOpenResult.Failed
            }
        return runCatching {
            val diagnostics = desktopPlayerLaunchDiagnostics(install, request, command)
            DesktopRuntimeLog.info(
                "externalPlayer command prepared id=${diagnostics.playerId} kind=${diagnostics.kind} " +
                    "sourceKind=${diagnostics.sourceKind} sourceKey=${diagnostics.sourceKey} " +
                    "sourceExt=${diagnostics.sourceExtension ?: "none"} headers=${diagnostics.headerNames} " +
                    "initialPositionMs=${diagnostics.initialPositionMs} " +
                    "seekNote=${diagnostics.seekSupportNote} command=${diagnostics.commandPreview}",
            )
            val startMs = System.currentTimeMillis()
            val process = ProcessBuilder(command)
                .redirectOutput(Redirect.DISCARD)
                .redirectError(Redirect.DISCARD)
                .start()
            val processPid = runCatching { process.pid() }.getOrNull()
            DesktopRuntimeLog.info(
                "externalPlayer launched id=${install.definition.id} pid=${processPid ?: "unknown"} " +
                    "elapsedLaunchMs=${System.currentTimeMillis() - startMs} executable=${install.executablePath}",
            )
            DesktopExternalPlaybackWindowController.minimizeToTray(install.definition.id, processPid)
            ExternalPlayerOpenResult.Opened
        }.getOrElse { throwable ->
            DesktopRuntimeLog.error("External player launch failed id=${install.definition.id}", throwable)
            ExternalPlayerOpenResult.Failed
        }
    }

    actual fun buildIntent(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerIntentResult {
        if (playerId.isNullOrBlank()) return ExternalPlayerIntentResult.NotConfigured

        if (playerId.startsWith(CUSTOM_PLAYER_PREFIX)) {
            return buildCustomPlayerIntent(request, playerId)
        }

        val install = detectedPlayers.firstOrNull { it.definition.id == playerId }
            ?: return ExternalPlayerIntentResult.NotConfigured
        val commandResult = buildDesktopPlayerCommand(install, request)
        val command = commandResult.command
            ?: return ExternalPlayerIntentResult.Failed
        return ExternalPlayerIntentResult.Success(command)
    }

    private fun openCustomPlayer(
        request: ExternalPlayerPlaybackRequest,
        playerId: String,
    ): ExternalPlayerOpenResult {
        val executablePath = playerId.removePrefix(CUSTOM_PLAYER_PREFIX)
        val exeFile = File(executablePath)
        if (!exeFile.isFile) {
            DesktopRuntimeLog.warn("custom external player not found at $executablePath")
            return ExternalPlayerOpenResult.NoPlayerAvailable
        }
        val kind = resolvePlayerKind(executablePath)
        val install = DesktopPlayerInstall(
            definition = DesktopPlayerDefinition(
                id = playerId,
                name = exeFile.nameWithoutExtension,
                kind = kind,
            ),
            executablePath = executablePath,
        )
        val commandResult = buildDesktopPlayerCommand(install, request)
        val command = commandResult.command
            ?: run {
                DesktopRuntimeLog.warn(
                    "Custom external player launch rejected: exe=$executablePath reason=${commandResult.failureReason}",
                )
                return ExternalPlayerOpenResult.Failed
            }
        return runCatching {
            val startMs = System.currentTimeMillis()
            val process = ProcessBuilder(command)
                .redirectOutput(Redirect.DISCARD)
                .redirectError(Redirect.DISCARD)
                .start()
            val processPid = runCatching { process.pid() }.getOrNull()
            DesktopRuntimeLog.info(
                "custom externalPlayer launched exe=$executablePath kind=$kind " +
                    "pid=${processPid ?: "unknown"} elapsedLaunchMs=${System.currentTimeMillis() - startMs}",
            )
            DesktopExternalPlaybackWindowController.minimizeToTray(playerId, processPid)
            ExternalPlayerOpenResult.Opened
        }.getOrElse { throwable ->
            DesktopRuntimeLog.error("Custom external player launch failed exe=$executablePath", throwable)
            ExternalPlayerOpenResult.Failed
        }
    }

    private fun buildCustomPlayerIntent(
        request: ExternalPlayerPlaybackRequest,
        playerId: String,
    ): ExternalPlayerIntentResult {
        val executablePath = playerId.removePrefix(CUSTOM_PLAYER_PREFIX)
        if (!File(executablePath).isFile) return ExternalPlayerIntentResult.Failed
        val kind = resolvePlayerKind(executablePath)
        val install = DesktopPlayerInstall(
            definition = DesktopPlayerDefinition(
                id = playerId,
                name = File(executablePath).nameWithoutExtension,
                kind = kind,
            ),
            executablePath = executablePath,
        )
        val commandResult = buildDesktopPlayerCommand(install, request)
        val command = commandResult.command
            ?: return ExternalPlayerIntentResult.Failed
        return ExternalPlayerIntentResult.Success(command)
    }

    private fun resolvePlayerKind(executablePath: String): DesktopPlayerKind {
        val name = File(executablePath).name.lowercase()
        return when {
            name.contains("mpc") -> DesktopPlayerKind.Mpc
            name.contains("vlc") -> DesktopPlayerKind.Vlc
            name.contains("mpv") -> DesktopPlayerKind.Mpv
            name.contains("kodi") -> DesktopPlayerKind.Kodi
            name.contains("iina") -> DesktopPlayerKind.Iina
            else -> DesktopPlayerKind.Vlc
        }
    }
}
