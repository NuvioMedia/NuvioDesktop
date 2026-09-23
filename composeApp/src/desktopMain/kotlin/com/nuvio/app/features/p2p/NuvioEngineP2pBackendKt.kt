package com.nuvio.app.features.p2p

import com.nuvio.app.features.player.DesktopBufferPreset
import com.nuvio.engine.NuvioEngineConfig
import com.nuvio.engine.NuvioTorrentProfile
import com.nuvio.engine.NuvioUploadMode
import java.io.File

internal const val UNKNOWN_TORRENT_ERROR = "Unknown torrent error"
internal const val STREAMING_SAMPLE_INTERVAL_MS = 5000L
internal const val STARTUP_SAMPLE_INTERVAL_MS = 1000L
const val METADATA_DEADLINE_MS = 60000L
const val METADATA_STALL_MIN_WAIT_MS = 20000L
const val METADATA_STALL_MS = 15000L

fun nuvioEngineWindowBytes(preset: DesktopBufferPreset = DesktopBufferPreset.Balanced): Long = when (preset) {
    DesktopBufferPreset.Metered -> 64L * 1024L * 1024L
    DesktopBufferPreset.LowData -> 128L * 1024L * 1024L
    DesktopBufferPreset.Balanced -> 256L * 1024L * 1024L
    DesktopBufferPreset.Resilient -> 512L * 1024L * 1024L
}

fun buildNuvioEngineConfig(
    stateDirectory: File,
    cacheDirectory: File,
    uploadEnabled: Boolean,
    torrentProfile: P2pTorrentProfile,
    diskCacheCapacityBytes: Long,
    windowBytes: Long = nuvioEngineWindowBytes(DesktopBufferPreset.Balanced),
): NuvioEngineConfig {
    val nativeProfile = when (torrentProfile) {
        P2pTorrentProfile.SOFT -> NuvioTorrentProfile.Soft
        P2pTorrentProfile.BALANCED -> NuvioTorrentProfile.Balanced
        P2pTorrentProfile.FAST -> NuvioTorrentProfile.Fast
    }
    return NuvioEngineConfig(
        dataDirectory = stateDirectory,
        cacheDirectory = cacheDirectory,
        memoryCacheCapacityBytes = windowBytes,
        diskCacheCapacityBytes = diskCacheCapacityBytes,
        torrentProfile = nativeProfile,
        uploadMode = if (uploadEnabled) NuvioUploadMode.Unlimited else NuvioUploadMode.Disabled,
        streamInactivityTimeoutMilliseconds = 0,
    )
}

fun unexpectedStreamStopError(
    requestId: Long,
    eventStreamId: String?,
    currentStreamId: String?,
    message: String?,
    fallbackMessage: String,
): P2pStreamingState.Error? {
    if (requestId != 0L || currentStreamId == null || eventStreamId != currentStreamId) {
        return null
    }
    val resolvedMessage = message?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackMessage
    return P2pStreamingState.Error(resolvedMessage)
}

fun unexpectedTorrentError(
    requestId: Long,
    eventTorrentId: String?,
    currentTorrentId: String?,
    message: String?,
    fallbackMessage: String,
): P2pStreamingState.Error? {
    if (requestId != 0L || eventTorrentId == null || currentTorrentId == null || eventTorrentId != currentTorrentId) {
        return null
    }
    val resolvedMessage = message?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackMessage
    return P2pStreamingState.Error(resolvedMessage)
}

fun metadataWaitVerdict(waitedMs: Long, stalledForMs: Long, knownPeers: Long): String? {
    return when {
        waitedMs >= METADATA_DEADLINE_MS ->
            "Torrent metadata did not arrive within 60s"
        waitedMs >= METADATA_STALL_MIN_WAIT_MS && stalledForMs >= METADATA_STALL_MS -> {
            if (knownPeers == 0L) {
                "No peers found for this torrent"
            } else {
                "No reachable peers for this torrent ($knownPeers found, none answered)"
            }
        }
        else -> null
    }
}
