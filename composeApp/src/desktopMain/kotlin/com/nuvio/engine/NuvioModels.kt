package com.nuvio.engine

public enum class NuvioEventType(public val nativeValue: Int) {
    TorrentAdded(1),
    TorrentMetadataReady(2),
    TorrentError(3),
    StreamPrepared(4),
    TorrentRemoved(5),
    StreamStopped(6),
    DiskCacheReclaimed(7);

    public companion object {
        public fun fromNative(value: Int): NuvioEventType =
            entries.firstOrNull { it.nativeValue == value }
                ?: throw NuvioEngineException(-1, "unknown native event type $value")
    }
}

public enum class NuvioTorrentProfile(internal val nativeValue: Int) {
    Soft(0),
    Balanced(1),
    Fast(2);
}

public enum class NuvioUploadMode(internal val nativeValue: Int) {
    Disabled(0),
    Unlimited(1),
    Limited(2);
}

public data class NuvioTorrentFile(
    val index: Int,
    val offset: Long,
    val size: Long,
    val path: String,
    val pathTruncated: Boolean,
)

public data class NuvioStream(
    val id: String,
    val url: String,
    val torrentId: String,
    val fileIndex: Int,
    val fileSize: Long,
)

public data class NuvioStreamStats(
    val fileIndex: Int,
    val fileSize: Long,
    val contiguousReadyBytes: Long,
    val verifiedFileBytes: Long,
    val deliveredBytes: Long,
    val activeDemands: Int = 0,
    val scheduledPieces: Int = 0,
    val blockingPieces: Int = 0,
    val primaryBlockingPiece: Int = -1,
    val secondaryBlockingPiece: Int = -1,
    val lastReadyPiece: Int = -1,
    val primaryDemandStart: Long = 0L,
    val primaryDemandEnd: Long = 0L,
    val secondaryDemandStart: Long = 0L,
    val secondaryDemandEnd: Long = 0L,
    val scheduleRevision: Long = 0L,
) {
    val bufferProgress: Float
        get() = ratio(contiguousReadyBytes, fileSize)

    val fileProgress: Float
        get() = ratio(verifiedFileBytes, fileSize)
}

public data class NuvioEngineStats(
    val activeTorrents: Int = 0,
    val activeStreams: Int = 0,
    val activeHttpRequests: Int = 0,
    val connectedPeers: Int = 0,
    val connectedSeeds: Int = 0,
    val knownPeers: Int = 0,
    val connectCandidates: Int = 0,
    val interestedPeers: Int = 0,
    val unchokedPeers: Int = 0,
    val downloadingPeers: Int = 0,
    val snubbedPeers: Int = 0,
    val pendingBlockRequests: Int = 0,
    val targetBlockRequests: Int = 0,
    val timedOutBlockRequests: Int = 0,
    val connectingPeers: Int = 0,
    val handshakingPeers: Int = 0,
    val targetPiecePeers: Int = 0,
    val targetPieceUnchokedPeers: Int = 0,
    val targetPieceDownloadingPeers: Int = 0,
    val offTargetDownloadingPeers: Int = 0,
    val trackerReplyEvents: Int = 0,
    val trackerErrorEvents: Int = 0,
    val dhtReplyEvents: Int = 0,
    val trackerPeersReturned: Long = 0L,
    val dhtPeersReturned: Long = 0L,
    val peerConnectEvents: Long = 0L,
    val peerDisconnectEvents: Long = 0L,
    val peerDisconnectTimeouts: Long = 0L,
    val peerDisconnectConnectFailures: Long = 0L,
    val peerDisconnectRedundant: Long = 0L,
    val peerDisconnectTurnover: Long = 0L,
    val peerDisconnectOther: Long = 0L,
    val torrentFinishedEvents: Long = 0L,
    val pendingPieceReads: Int = 0,
    val downloadRateBytesPerSecond: Long = 0L,
    val uploadRateBytesPerSecond: Long = 0L,
    val totalPayloadDownloadBytes: Long = 0L,
    val totalPayloadUploadBytes: Long = 0L,
    val memoryCacheCapacityBytes: Long = 0L,
    val memoryCacheUsedBytes: Long = 0L,
    val memoryCacheHits: Long = 0L,
    val memoryCacheMisses: Long = 0L,
    val memoryCacheEvictions: Long = 0L,
    val memoryCacheEntries: Long = 0L,
    val warmTorrents: Int = 0,
    val quiescedTorrents: Int = 0,
    val diskCacheCapacityBytes: Long = 0L,
    val diskCacheUsedBytes: Long = 0L,
    val diskCacheProtectedBytes: Long = 0L,
    val diskCacheEvictions: Long = 0L,
    val diskCacheReclaimedBytes: Long = 0L,
    val diskCacheOverBudget: Boolean = false,
)

public data class NuvioEvent(
    val type: NuvioEventType,
    val sequence: Long,
    val requestId: Long,
    val droppedEvents: Long,
    val torrentId: String?,
    val message: String?,
    val fileIndex: Int?,
    val fileSize: Long,
    val streamId: String?,
    val streamUrl: String?,
)

public class NuvioEngineException(
    public val status: Int,
    message: String,
) : Exception(message)

private fun ratio(value: Long, total: Long): Float {
    if (total <= 0L) return 0f
    return (value.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
}
