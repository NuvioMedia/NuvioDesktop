package com.nuvio.app.features.p2p

import com.nuvio.engine.NuvioEngineConfig
import com.nuvio.engine.NuvioTorrentProfile
import com.nuvio.engine.NuvioUploadMode
import java.io.File

internal fun buildNuvioEngineConfig(
    stateDirectory: File,
    cacheDirectory: File,
    uploadEnabled: Boolean,
    torrentProfile: P2pTorrentProfile,
    diskCacheCapacityBytes: Long,
): NuvioEngineConfig = NuvioEngineConfig(
    dataDirectory = stateDirectory,
    cacheDirectory = cacheDirectory,
    diskCacheCapacityBytes = diskCacheCapacityBytes,
    torrentProfile = when (torrentProfile) {
        P2pTorrentProfile.SOFT -> NuvioTorrentProfile.Soft
        P2pTorrentProfile.BALANCED -> NuvioTorrentProfile.Balanced
        P2pTorrentProfile.FAST -> NuvioTorrentProfile.Fast
    },
    uploadMode = if (uploadEnabled) {
        NuvioUploadMode.Unlimited
    } else {
        NuvioUploadMode.Disabled
    },
    streamInactivityTimeoutMilliseconds = 0,
)

internal fun migrateNestedPayloadDirectory(cacheDirectory: File) {
    val payloadDirectory = File(cacheDirectory, "payload")
    val nestedDirectory = File(payloadDirectory, "payload")
    val entries = nestedDirectory.listFiles() ?: return
    entries.forEach { entry ->
        val target = File(payloadDirectory, entry.name)
        if (target.exists() || !entry.renameTo(target)) {
            entry.deleteRecursively()
        }
    }
    nestedDirectory.deleteRecursively()
}

internal fun unexpectedStreamStopError(
    requestId: Long,
    eventStreamId: String?,
    currentStreamId: String?,
    message: String?,
    fallbackMessage: String,
): P2pStreamingState.Error? {
    if (requestId != 0L || currentStreamId == null || eventStreamId != currentStreamId) {
        return null
    }
    return P2pStreamingState.Error(message?.trim()?.takeIf(String::isNotEmpty) ?: fallbackMessage)
}

internal fun unexpectedTorrentError(
    requestId: Long,
    eventTorrentId: String?,
    currentTorrentId: String?,
    message: String?,
    fallbackMessage: String,
): P2pStreamingState.Error? {
    if (requestId != 0L ||
        eventTorrentId == null ||
        currentTorrentId == null ||
        eventTorrentId != currentTorrentId
    ) {
        return null
    }
    return P2pStreamingState.Error(message?.trim()?.takeIf(String::isNotEmpty) ?: fallbackMessage)
}
