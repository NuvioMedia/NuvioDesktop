package com.nuvio.app.features.player

internal enum class DesktopPlayerKind {
    Mpc, Vlc, Mpv, Kodi, Iina,
}

internal data class DesktopPlayerDefinition(
    val id: String,
    val name: String,
    val kind: DesktopPlayerKind,
)

internal data class DesktopPlayerInstall(
    val definition: DesktopPlayerDefinition,
    val executablePath: String,
)

internal data class DesktopPlayerCommandResult(
    val command: List<String>?,
    val failureReason: String? = null,
    val logFilePath: String? = null,
)

internal data class DesktopPlayerLaunchDiagnostics(
    val playerId: String,
    val playerName: String,
    val kind: DesktopPlayerKind,
    val executablePath: String,
    val sourceKind: String,
    val sourceKey: String,
    val sourceExtension: String?,
    val hasSeparateAudio: Boolean = false,
    val headerNames: List<String> = emptyList(),
    val initialPositionMs: Long = 0L,
    val commandPreview: List<String>,
    val seekSupportNote: String,
)
