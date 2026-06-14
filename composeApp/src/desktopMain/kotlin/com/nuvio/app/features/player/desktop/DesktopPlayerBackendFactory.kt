package com.nuvio.app.features.player.desktop

import com.nuvio.app.desktop.DesktopRuntimeLog
import com.nuvio.app.features.player.desktop.mpv.MpvDesktopPlayerBackend
import com.nuvio.app.features.player.desktop.mpv.MpvRuntimeBootstrap
import com.nuvio.app.features.player.desktop.mpv.MpvRuntimeLocator

internal object DesktopPlayerBackendFactory {
    private const val BACKEND_PROPERTY = "nuvio.player.backend"
    private const val BACKEND_ENV = "NUVIO_PLAYER_BACKEND"

    fun createDesktopBackend(): DesktopPlayerBackend {
        val selection = BackendSelection.resolve()
        DesktopRuntimeLog.info("Selected player backend request=${selection.value} source=${selection.source}")
        return createMpvOrUnavailable(selection)
    }

    private fun createMpvOrUnavailable(selection: BackendSelection): DesktopPlayerBackend =
        createMpvOrNull(selection) ?: unavailable(
            backendName = "mediamp-mpv",
            technicalMessage = "MPV backend is unavailable.",
            selection = selection,
        )

    private fun createMpvOrNull(selection: BackendSelection): DesktopPlayerBackend? {
        val runtime = MpvRuntimeLocator.resolve()
        val bootstrap = MpvRuntimeBootstrap.apply(runtime)
        if (!bootstrap.success) {
            DesktopRuntimeLog.error("MPV runtime bootstrap failed diagnostics=${bootstrap.diagnostics}", bootstrap.error)
            return null
        }
        return MpvDesktopPlayerBackend.create(runtime)
            .onSuccess {
                DesktopRuntimeLog.info("Selected player backend=${it.backendName} (source=${selection.source} request=${selection.value})")
            }
            .onFailure { DesktopRuntimeLog.error("MPV backend init failed", it) }
            .getOrNull()
    }

    private fun unavailable(
        backendName: String,
        technicalMessage: String,
        selection: BackendSelection,
    ): DesktopPlayerBackend {
        DesktopRuntimeLog.warn("Selected player backend=$backendName (source=${selection.source} request=${selection.value})")
        return UnavailableDesktopPlayerBackend(
            backendName = backendName,
            error = DesktopPlayerError.RuntimeUnavailable(
                backendName = backendName,
                technicalMessage = technicalMessage,
                suggestedAction = "Check the backend runtime files and restart the app.",
            ),
        )
    }

    private data class BackendSelection(
        val value: String,
        val source: String,
    ) {
        companion object {
            fun resolve(): BackendSelection {
                val property = System.getProperty(BACKEND_PROPERTY)?.trim()?.lowercase()
                if (!property.isNullOrBlank()) return BackendSelection(property, "system-property:$BACKEND_PROPERTY")
                val env = System.getenv(BACKEND_ENV)?.trim()?.lowercase()
                if (!env.isNullOrBlank()) return BackendSelection(env, "env:$BACKEND_ENV")
                return BackendSelection("auto", "default")
            }
        }
    }
}
