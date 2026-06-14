package com.nuvio.app.features.player

import com.nuvio.app.desktop.DesktopRuntimeLog
import com.nuvio.app.desktop.DesktopExternalPlaybackWindowController
import java.io.File
import java.lang.ProcessBuilder.Redirect

internal actual object ExternalPlayerPlatform {
    private val isWindows: Boolean by lazy {
        System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true
    }

    private val allDefinitions: List<DesktopPlayerDefinition> by lazy {
        if (isWindows) windowsDesktopPlayerDefinitions else linuxDesktopPlayerDefinitions
    }

    private val detectedPlayers: List<DesktopPlayerInstall> by lazy {
        val players = if (isWindows) {
            detectWindowsExternalPlayers().map { it.toDesktopPlayerInstall() }
        } else {
            detectLinuxExternalPlayers().map { it.toDesktopPlayerInstall() }
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
        val install = detectedPlayers.firstOrNull { it.definition.id == playerId }
            ?: return ExternalPlayerIntentResult.NotConfigured
        val commandResult = buildDesktopPlayerCommand(install, request)
        val command = commandResult.command
            ?: return ExternalPlayerIntentResult.Failed
        return ExternalPlayerIntentResult.Success(command)
    }
}
