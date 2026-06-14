package com.nuvio.app.features.player.desktop

internal sealed class DesktopPlayerError(
    val uiMessage: String,
    val technicalMessage: String,
    val backendName: String,
    val recoverable: Boolean,
    val suggestedAction: String? = null,
    val cause: Throwable? = null,
) {
    class RuntimeUnavailable(
        backendName: String,
        technicalMessage: String,
        suggestedAction: String? = null,
        cause: Throwable? = null,
    ) : DesktopPlayerError(
        uiMessage = "Player runtime is unavailable.",
        technicalMessage = technicalMessage,
        backendName = backendName,
        recoverable = true,
        suggestedAction = suggestedAction,
        cause = cause,
    )

    class BackendInitializationFailed(
        backendName: String,
        technicalMessage: String,
        cause: Throwable? = null,
    ) : DesktopPlayerError(
        uiMessage = "Player failed to start.",
        technicalMessage = technicalMessage,
        backendName = backendName,
        recoverable = true,
        cause = cause,
    )

    class MediaLoadFailed(
        backendName: String,
        technicalMessage: String,
        cause: Throwable? = null,
    ) : DesktopPlayerError(
        uiMessage = "Failed to load media.",
        technicalMessage = technicalMessage,
        backendName = backendName,
        recoverable = true,
        cause = cause,
    )

    class PlaybackFailed(
        backendName: String,
        technicalMessage: String,
        cause: Throwable? = null,
        uiMessage: String = "Playback error.",
    ) : DesktopPlayerError(
        uiMessage = uiMessage,
        technicalMessage = technicalMessage,
        backendName = backendName,
        recoverable = true,
        cause = cause,
    )

    class NativeLibraryLoadFailed(
        backendName: String,
        technicalMessage: String,
        cause: Throwable? = null,
    ) : DesktopPlayerError(
        uiMessage = "Player native library failed to load.",
        technicalMessage = technicalMessage,
        backendName = backendName,
        recoverable = true,
        cause = cause,
    )

    class UnsupportedOperation(
        backendName: String,
        technicalMessage: String,
    ) : DesktopPlayerError(
        uiMessage = "Player operation is not supported.",
        technicalMessage = technicalMessage,
        backendName = backendName,
        recoverable = true,
    )

    class InvalidSource(
        backendName: String,
        technicalMessage: String,
    ) : DesktopPlayerError(
        uiMessage = "Invalid media source.",
        technicalMessage = technicalMessage,
        backendName = backendName,
        recoverable = true,
    )

    class Disposed(
        backendName: String,
        technicalMessage: String,
    ) : DesktopPlayerError(
        uiMessage = "Player was closed.",
        technicalMessage = technicalMessage,
        backendName = backendName,
        recoverable = false,
    )

    class Unknown(
        backendName: String,
        technicalMessage: String,
        cause: Throwable? = null,
    ) : DesktopPlayerError(
        uiMessage = "Unknown player error.",
        technicalMessage = technicalMessage,
        backendName = backendName,
        recoverable = true,
        cause = cause,
    )
}
