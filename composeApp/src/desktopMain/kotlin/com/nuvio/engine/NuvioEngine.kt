package com.nuvio.engine

import com.nuvio.engine.internal.NativeBridge
import com.nuvio.engine.internal.NativeEventPayload
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

public class NuvioEngine private constructor(
    initialHandle: Long,
    private val dispatcher: CoroutineDispatcher,
) : Closeable {
    private data class PendingCommand(
        val expectedType: NuvioEventType,
        val completion: CompletableDeferred<NuvioEvent>,
    )

    private val nativeLock = Any()
    private var nativeHandle: Long = initialHandle
    @Volatile
    private var pollFailure: Throwable? = null
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + dispatcher)
    private val pendingCommands = ConcurrentHashMap<Long, PendingCommand>()
    private val mutableEvents = MutableSharedFlow<NuvioEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutableStats = MutableStateFlow(NuvioEngineStats())
    private var lastDroppedEvents = 0L

    public val events: Flow<NuvioEvent> = mutableEvents.asSharedFlow()
    public val stats: StateFlow<NuvioEngineStats> = mutableStats.asStateFlow()

    private val eventLoop = scope.launch {
        try {
            var nextStatsSampleNanos = 0L
            while (currentCoroutineContext().isActive) {
                while (true) {
                    val payload = synchronized(nativeLock) {
                        if (nativeHandle == 0L) null else NativeBridge.nativePollEvent(nativeHandle)
                    } ?: break
                    processEvent(payload)
                }

                val now = System.nanoTime()
                if (now >= nextStatsSampleNanos) {
                    sampleStats()
                    nextStatsSampleNanos = now + 1_000_000_000L
                }
                delay(EVENT_POLL_INTERVAL_MILLISECONDS)
            }
        } catch (error: Throwable) {
            if (error !is CancellationException) {
                pollFailure = error
                failPending(error)
            }
        }
    }

    public suspend fun addMagnet(magnetUri: String): String {
        require(magnetUri.isNotBlank()) { "magnet URI must not be blank" }
        require(magnetUri.toByteArray(Charsets.UTF_8).size <= MAXIMUM_MAGNET_BYTES) {
            "magnet URI exceeds the 16 KiB native limit"
        }
        val event = awaitCommand(NuvioEventType.TorrentMetadataReady) { handle ->
            NativeBridge.nativeAddMagnet(handle, magnetUri)
        }
        return event.torrentId
            ?: throw NuvioEngineException(-1, "metadata-ready event did not contain a torrent ID")
    }

    public suspend fun addTorrent(torrentData: ByteArray): String {
        require(torrentData.isNotEmpty()) { "torrent data must not be empty" }
        require(torrentData.size <= MAXIMUM_TORRENT_BYTES) {
            "torrent data exceeds the 4 MiB native limit"
        }
        val event = awaitCommand(NuvioEventType.TorrentMetadataReady) { handle ->
            NativeBridge.nativeAddTorrentData(handle, torrentData)
        }
        return event.torrentId
            ?: throw NuvioEngineException(-1, "metadata-ready event did not contain a torrent ID")
    }

    public suspend fun files(torrentId: String): List<NuvioTorrentFile> = withContext(dispatcher) {
        validateTorrentId(torrentId)
        val result = synchronized(nativeLock) {
            ensureOpen()
            NativeBridge.nativeGetFiles(nativeHandle, torrentId)
        }
        checkStatus(result.status)
        result.files.map { file ->
            NuvioTorrentFile(
                index = file.index,
                offset = file.offset,
                size = file.size,
                path = file.path,
                pathTruncated = file.pathTruncated,
            )
        }
    }

    public suspend fun prepareStream(
        torrentId: String,
        fileIndex: Int? = null,
        filenameHint: String? = null,
        minimumContiguousBytes: Long = 0L,
    ): NuvioStream {
        validateTorrentId(torrentId)
        require(fileIndex == null || fileIndex >= 0) { "file index must be non-negative" }
        require(minimumContiguousBytes in 0L..MAXIMUM_PRELOAD_BYTES) {
            "minimum contiguous bytes must be between 0 and 64 MiB"
        }
        val event = awaitCommand(NuvioEventType.StreamPrepared) { handle ->
            NativeBridge.nativePrepareStream(
                handle,
                torrentId,
                fileIndex ?: -1,
                filenameHint,
            )
        }
        val stream = NuvioStream(
            id = event.streamId
                ?: throw NuvioEngineException(-1, "prepared event did not contain a stream ID"),
            url = event.streamUrl
                ?: throw NuvioEngineException(-1, "prepared event did not contain a stream URL"),
            torrentId = event.torrentId ?: torrentId,
            fileIndex = event.fileIndex
                ?: throw NuvioEngineException(-1, "prepared event did not contain a file index"),
            fileSize = event.fileSize,
        )
        if (minimumContiguousBytes > 0L) {
            try {
                preloadStream(stream, minimumContiguousBytes)
            } catch (error: Throwable) {
                try {
                    stopStream(stream.id)
                } catch (_: Throwable) {
                    // Preserve the preload failure while best-effort cleanup unwinds.
                }
                throw error
            }
        }
        return stream
    }

    public suspend fun currentStreamStats(streamId: String): NuvioStreamStats =
        withContext(dispatcher) {
            validateStreamId(streamId)
            val values = synchronized(nativeLock) {
                ensureOpen()
                NativeBridge.nativeGetStreamStats(nativeHandle, streamId)
            }
            require(values.size == NATIVE_STREAM_STATS_VALUE_COUNT) {
                "invalid native stream stats result"
            }
            checkStatus(values[0].toInt())
            NuvioStreamStats(
                fileIndex = values[1].toInt(),
                fileSize = values[2],
                contiguousReadyBytes = values[3],
                verifiedFileBytes = values[4],
                deliveredBytes = values[5],
                activeDemands = values[6].toInt(),
                scheduledPieces = values[7].toInt(),
                blockingPieces = values[8].toInt(),
                primaryBlockingPiece = values[9].toInt(),
                secondaryBlockingPiece = values[10].toInt(),
                lastReadyPiece = values[11].toInt(),
                primaryDemandStart = values[12],
                primaryDemandEnd = values[13],
                secondaryDemandStart = values[14],
                secondaryDemandEnd = values[15],
                scheduleRevision = values[16],
            )
        }

    public suspend fun preloadStream(
        stream: NuvioStream,
        minimumContiguousBytes: Long,
    ): NuvioStreamStats {
        require(minimumContiguousBytes in 0L..MAXIMUM_PRELOAD_BYTES) {
            "minimum contiguous bytes must be between 0 and 64 MiB"
        }
        require(stream.fileSize >= 0L) { "stream file size must be non-negative" }
        require(stream.fileIndex >= 0) { "stream file index must be non-negative" }
        validateStreamId(stream.id)
        val target = minimumContiguousBytes.coerceAtMost(stream.fileSize)
        if (target == 0L) {
            return currentStreamStats(stream.id)
        }
        val uri = URI(stream.url)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65_535) {
            "stream URL must be an IPv4 loopback HTTP endpoint"
        }
        require(uri.rawPath == "/stream/${stream.id}") { "stream URL token does not match stream ID" }
        runInterruptible(dispatcher) {
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 35_000
                connection.setRequestProperty("Range", "bytes=0-${target - 1}")
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK &&
                    status != HttpURLConnection.HTTP_PARTIAL) {
                    throw NuvioEngineException(-1, "loopback preload returned HTTP $status")
                }
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = target
                    while (remaining > 0L) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) {
                            throw NuvioEngineException(-1, "loopback preload ended before its target")
                        }
                        remaining -= read
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        repeat(40) {
            val stats = currentStreamStats(stream.id)
            if (stats.contiguousReadyBytes >= target) {
                return stats
            }
            delay(25)
        }
        throw NuvioEngineException(-1, "verified preload target was not reflected in stream stats")
    }

    public suspend fun stopStream(streamId: String) {
        validateStreamId(streamId)
        awaitCommand(NuvioEventType.StreamStopped) { handle ->
            NativeBridge.nativeStopStream(handle, streamId)
        }
    }

    public suspend fun removeTorrent(torrentId: String) {
        validateTorrentId(torrentId)
        awaitCommand(NuvioEventType.TorrentRemoved) { handle ->
            NativeBridge.nativeRemoveTorrent(handle, torrentId)
        }
    }

    public suspend fun reclaimDiskCache(targetBytes: Long = 0L): NuvioEvent {
        require(targetBytes >= 0) { "disk cache target must be non-negative" }
        return awaitCommand(NuvioEventType.DiskCacheReclaimed) { handle ->
            NativeBridge.nativeReclaimDiskCache(handle, targetBytes)
        }
    }

    public suspend fun shutdown() {
        withContext(Dispatchers.IO) {
            close()
        }
    }

    override fun close() {
        scope.cancel()
        val handle = synchronized(nativeLock) {
            val current = nativeHandle
            nativeHandle = 0L
            current
        }
        if (handle == 0L) {
            return
        }
        pendingCommands.values.forEach { pending ->
            pending.completion.cancel()
        }
        pendingCommands.clear()
        NativeBridge.nativeDestroy(handle)
    }

    private suspend fun awaitCommand(
        expectedType: NuvioEventType,
        submit: (Long) -> LongArray,
    ): NuvioEvent {
        val pending = PendingCommand(expectedType, CompletableDeferred())
        val requestId = synchronized(nativeLock) {
            ensureOpen()
            val result = submit(nativeHandle)
            require(result.size >= 2) { "invalid native command result" }
            checkStatus(result[0].toInt())
            val id = result[1]
            pendingCommands[id] = pending
            id
        }
        return try {
            pending.completion.await()
        } finally {
            pendingCommands.remove(requestId, pending)
        }
    }

    private fun processEvent(payload: NativeEventPayload) {
        val event = payload.toPublicEvent()
        if (event.droppedEvents > lastDroppedEvents) {
            lastDroppedEvents = event.droppedEvents
            val error = NuvioEngineException(
                -1,
                "native event queue dropped ${event.droppedEvents} event(s); resynchronize state",
            )
            pendingCommands.values.forEach { it.completion.completeExceptionally(error) }
            pendingCommands.clear()
        }

        mutableEvents.tryEmit(event)
        if (event.requestId == 0L) {
            return
        }
        val pending = pendingCommands[event.requestId] ?: return
        when {
            event.type == NuvioEventType.TorrentError -> {
                pending.completion.completeExceptionally(
                    NuvioEngineException(-1, event.message ?: "torrent operation failed"),
                )
            }
            event.type == pending.expectedType -> pending.completion.complete(event)
        }
    }

    private fun sampleStats() {
        val values = synchronized(nativeLock) {
            if (nativeHandle == 0L) return
            NativeBridge.nativeGetStats(nativeHandle)
        }
        if (values.size != NATIVE_STATS_VALUE_COUNT || values[0].toInt() != STATUS_OK) {
            return
        }
        mutableStats.value = NuvioEngineStats(
            activeTorrents = values[1].toInt(),
            activeStreams = values[2].toInt(),
            activeHttpRequests = values[3].toInt(),
            connectedPeers = values[4].toInt(),
            connectedSeeds = values[5].toInt(),
            pendingPieceReads = values[6].toInt(),
            downloadRateBytesPerSecond = values[7],
            uploadRateBytesPerSecond = values[8],
            totalPayloadDownloadBytes = values[9],
            totalPayloadUploadBytes = values[10],
            memoryCacheCapacityBytes = values[11],
            memoryCacheUsedBytes = values[12],
            memoryCacheHits = values[13],
            memoryCacheMisses = values[14],
            memoryCacheEvictions = values[15],
            memoryCacheEntries = values[16],
            warmTorrents = values[17].toInt(),
            quiescedTorrents = values[18].toInt(),
            diskCacheCapacityBytes = values[19],
            diskCacheUsedBytes = values[20],
            diskCacheProtectedBytes = values[21],
            diskCacheEvictions = values[22],
            diskCacheReclaimedBytes = values[23],
            diskCacheOverBudget = values[24] != 0L,
            knownPeers = values[25].toInt(),
            connectCandidates = values[26].toInt(),
            interestedPeers = values[27].toInt(),
            unchokedPeers = values[28].toInt(),
            downloadingPeers = values[29].toInt(),
            snubbedPeers = values[30].toInt(),
            pendingBlockRequests = values[31].toInt(),
            targetBlockRequests = values[32].toInt(),
            timedOutBlockRequests = values[33].toInt(),
            connectingPeers = values[34].toInt(),
            handshakingPeers = values[35].toInt(),
            targetPiecePeers = values[36].toInt(),
            targetPieceUnchokedPeers = values[37].toInt(),
            targetPieceDownloadingPeers = values[38].toInt(),
            offTargetDownloadingPeers = values[39].toInt(),
            trackerReplyEvents = values[40].toInt(),
            trackerErrorEvents = values[41].toInt(),
            dhtReplyEvents = values[42].toInt(),
            trackerPeersReturned = values[43],
            dhtPeersReturned = values[44],
            peerConnectEvents = values[45],
            peerDisconnectEvents = values[46],
            peerDisconnectTimeouts = values[47],
            peerDisconnectConnectFailures = values[48],
            peerDisconnectRedundant = values[49],
            peerDisconnectTurnover = values[50],
            peerDisconnectOther = values[51],
            torrentFinishedEvents = values[52],
        )
    }

    private fun ensureOpen() {
        check(nativeHandle != 0L) { "NuvioEngine is closed" }
        pollFailure?.let { failure ->
            throw IllegalStateException("NuvioEngine event polling failed", failure)
        }
    }

    private fun failPending(error: Throwable) {
        pendingCommands.values.forEach { pending ->
            pending.completion.completeExceptionally(error)
        }
        pendingCommands.clear()
    }

    private fun checkStatus(status: Int) {
        if (status != STATUS_OK) {
            throw NuvioEngineException(status, NativeBridge.nativeStatusMessage(status))
        }
    }

    public companion object {
        private const val STATUS_OK = 0
        private const val EVENT_POLL_INTERVAL_MILLISECONDS = 20L
        private const val NATIVE_STATS_VALUE_COUNT = 53
        private const val NATIVE_STREAM_STATS_VALUE_COUNT = 17
        private const val MAXIMUM_MAGNET_BYTES = 16 * 1024
        private const val MAXIMUM_TORRENT_BYTES = 4 * 1024 * 1024
        private const val MAXIMUM_PRELOAD_BYTES = 64L * 1024L * 1024L

        private fun validateTorrentId(torrentId: String) {
            require((torrentId.length == 40 || torrentId.length == 64) && torrentId.all(Char::isHexDigit)) {
                "torrent ID must be a 40- or 64-character hexadecimal hash"
            }
        }

        private fun validateStreamId(streamId: String) {
            require(streamId.length == 64 && streamId.all(Char::isHexDigit)) {
                "stream ID must be a 64-character hexadecimal token"
            }
        }

        public val version: String
            get() = NativeBridge.nativeEngineVersion()

        public val protocolBackendVersion: String
            get() = NativeBridge.nativeBackendVersion()

        @JvmStatic
        public fun create(
            config: NuvioEngineConfig,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): NuvioEngine {
            config.validate()
            val tlsCaBundle = config.tlsCaBundle
            if (tlsCaBundle != null) {
                config.validateTlsCaBundle(tlsCaBundle)
            }
            val result = NativeBridge.nativeCreate(
                dataDirectory = config.dataDirectory.absolutePath,
                cacheDirectory = config.cacheDirectory.absolutePath,
                memoryCacheCapacityBytes = config.memoryCacheCapacityBytes,
                diskCacheCapacityBytes = config.diskCacheCapacityBytes,
                torrentProfile = config.torrentProfile.nativeValue,
                listenPort = config.listenPort,
                uploadMode = config.uploadMode.nativeValue,
                uploadLimitBytesPerSecond = config.uploadLimitBytesPerSecond,
                streamInactivityTimeoutMilliseconds = config.streamInactivityTimeoutMilliseconds,
                warmTorrentTimeoutMilliseconds = config.warmTorrentTimeoutMilliseconds,
                tlsCaBundlePath = tlsCaBundle?.absolutePath ?: "",
            )
            require(result.size >= 2) { "invalid native create result" }
            val status = result[0].toInt()
            if (status != STATUS_OK) {
                throw NuvioEngineException(status, NativeBridge.nativeStatusMessage(status))
            }
            check(result[1] != 0L) { "native engine returned an empty handle" }
            return NuvioEngine(result[1], dispatcher)
        }
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun NativeEventPayload.toPublicEvent(): NuvioEvent = NuvioEvent(
    type = NuvioEventType.fromNative(type),
    sequence = sequence,
    requestId = requestId,
    droppedEvents = droppedEvents,
    torrentId = torrentId.ifEmpty { null },
    message = message.ifEmpty { null },
    fileIndex = fileIndex.takeIf { it >= 0 },
    fileSize = fileSize,
    streamId = streamId.ifEmpty { null },
    streamUrl = streamUrl.ifEmpty { null },
)
