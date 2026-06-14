package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import com.nuvio.app.desktop.DesktopRuntimeLog

@Composable
actual fun rememberExternalPlayerLauncher(
    onResult: (ExternalPlaybackResult?) -> Unit,
): (ExternalPlayerIntentResult.Success) -> Boolean = { intentResult ->
    val command = intentResult.intent as? List<*>
    if (command == null) {
        DesktopRuntimeLog.warn("externalPlayer launcher: invalid intent type")
        false
    } else {
        val typedCommand = command.filterIsInstance<String>()
        if (typedCommand.isEmpty()) {
            DesktopRuntimeLog.warn("externalPlayer launcher: empty command")
            false
        } else {
            runCatching {
                val process = ProcessBuilder(typedCommand)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                DesktopRuntimeLog.info("externalPlayer launched via intent command=${typedCommand.firstOrNull()}")
                onResult(null)
                true
            }.getOrDefault(false)
        }
    }
}
