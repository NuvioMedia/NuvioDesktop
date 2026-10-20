package com.nuvio.app.features.p2p

import co.touchlab.kermit.Logger
import com.nuvio.app.core.i18n.localizedP2pUnknownTorrentError
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.NuvioEngineRuntime
import com.nuvio.engine.NuvioEngineStats
import com.nuvio.engine.NuvioEventType
import com.nuvio.engine.NuvioStream
import com.nuvio.engine.NuvioStreamStats
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SAMPLE_INTERVAL_MS = 1_000L
private const val STATS_INTERVAL_MS = 250L

actual object P2pStreamingEngine {
    private data class EngineConfigurationKey(
        val uploadEnabled: Boolean,
        val torrentProfile: P2pTorrentProfile,
        val diskCacheCapacityBytes: Long,
    )

    private data class DetachedStream(
        val engine: NuvioEngine?,
        val streamId: String?,
    )

    private val log = Logger.withTag("P2pStreamingEngine")
    private val diagnostics = Logger.withTag("NuvioP2PDiag")
    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    actual val state: StateFlow<P2pStreamingState> = _state.asStateFlow()
    private val _cacheState = MutableStateFlow(P2pCacheUiState())
    actual val cacheState: StateFlow<P2pCacheUiState> = _cacheState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private val startMutex = Mutex()
    private val runtime: NuvioEngineRuntime by lazy {
        NuvioEngineRuntime.load(DesktopEngineLibrary.resolve())
    }
    private var statsJob: Job? = null
    private var cleanupJob: Job? = null
    private var engineEventsJob: Job? = null
    private var streamGeneration = 0L
    @Volatile
    private var currentTorrentId: String? = null
    @Volatile
    private var currentStreamId: String? = null
    @Volatile
    private var engine: NuvioEngine? = null
    private var engineConfigurationKey: EngineConfigurationKey? = null
    private val knownTorrentIds = mutableSetOf<String>()

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread({ runCatching { engine?.close() } }, "nuvio-engine-shutdown"),
        )
    }

    actual suspend fun startStream(request: P2pStreamRequest): String = withContext(Dispatchers.IO) {
        startMutex.withLock { startStreamLocked(request) }
    }

    actual suspend fun clearCache(): P2pCacheClearResult = withContext(Dispatchers.IO) {
        startMutex.withLock {
            check(_state.value !is P2pStreamingState.Streaming &&
                _state.value !is P2pStreamingState.Connecting) {
                "Torrent cache cannot be cleared during active playback"
            }
            _cacheState.value = _cacheState.value.copy(isClearing = true)
            try {
                val activeEngine = ensureEngine()
                val before = activeEngine.stats.value
                activeEngine.reclaimDiskCache(0L)
                delay(SAMPLE_INTERVAL_MS + 100L)
                val after = activeEngine.stats.value
                updateCacheState(after.diskCacheUsedBytes, after.diskCacheProtectedBytes)
                P2pCacheClearResult(
                    reclaimedBytes = (after.diskCacheReclaimedBytes - before.diskCacheReclaimedBytes)
                        .coerceAtLeast(0L),
                    remainingBytes = after.diskCacheUsedBytes,
                    protectedBytes = after.diskCacheProtectedBytes,
                )
            } finally {
                _cacheState.value = _cacheState.value.copy(isClearing = false)
            }
        }
    }

    actual fun stopStream() {
        scheduleStop(shutdownEngine = false)
    }

    actual fun shutdown() {
        scheduleStop(shutdownEngine = true)
    }

    private suspend fun startStreamLocked(request: P2pStreamRequest): String {
        val startedAtMs = nowMs()
        val phase = AtomicReference("stop_previous")
        diagnostics.i {
            "start phase=accepted hash=${diagnosticId(request.infoHash)} fileIndex=${request.fileIdx ?: -1} " +
                "filenameHint=${!request.filename.isNullOrBlank()} requestTrackers=${request.trackers.size}"
        }
        stopStreamNow(shutdownEngine = false)
        val generation = beginStreamGeneration()

        var activeEngine: NuvioEngine? = null
        var preparedStream: NuvioStream? = null
        var attached = false
        var startupStatsJob: Job? = null
        return try {
            phase.set("ensure_engine")
            val magnetUri = buildP2pMagnetUri(request.infoHash, (DEFAULT_TRACKERS + request.trackers).distinct())
            val resolvedEngine = ensureEngine()
            activeEngine = resolvedEngine
            val payloadDownloadBaseline = resolvedEngine.stats.value.totalPayloadDownloadBytes
            ensureCurrentGeneration(generation)
            startupStatsJob = startStartupStatsPolling(resolvedEngine, generation, phase)

            phase.set("add_magnet")
            val canonicalHash = canonicalP2pInfoHash(request.infoHash)
            val reusedTorrent = canonicalHash in knownTorrentIds
            val torrentId = if (reusedTorrent) {
                canonicalHash
            } else {
                resolvedEngine.addMagnet(magnetUri).also { knownTorrentIds += it }
            }
            ensureCurrentGeneration(generation)
            logPhase(startedAtMs, phase.get(), "torrent=${diagnosticId(torrentId)} cached=$reusedTorrent")

            phase.set("prepare_stream")
            val stream = resolvedEngine.prepareStream(
                torrentId = torrentId,
                fileIndex = request.fileIdx,
                filenameHint = request.filename,
            )
            preparedStream = stream
            logPhase(
                startedAtMs,
                phase.get(),
                "stream=${diagnosticId(stream.id)} selectedFile=${stream.fileIndex} fileBytes=${stream.fileSize}",
            )
            currentCoroutineContext().ensureActive()

            phase.set("attach_route")
            if (!attachStreamIfCurrent(generation, torrentId, stream.id)) {
                withContext(NonCancellable) { stopPreparedStream(resolvedEngine, stream.id) }
                preparedStream = null
                throw CancellationException("P2P stream start was cancelled")
            }
            attached = true

            startStatsPolling(resolvedEngine, stream, generation, payloadDownloadBaseline)
            val initial = resolvedEngine.stats.value
            val published = publishStreamingIfCurrent(
                generation = generation,
                state = P2pStreamingState.Streaming(
                    localUrl = stream.url,
                    downloadSpeed = initial.downloadRateBytesPerSecond,
                    uploadSpeed = initial.uploadRateBytesPerSecond,
                    peers = initial.connectedPeers,
                    seeds = initial.connectedSeeds,
                    bufferProgress = 0f,
                    totalProgress = 0f,
                    downloadedBytes = (initial.totalPayloadDownloadBytes - payloadDownloadBaseline)
                        .coerceAtLeast(0L),
                ),
            )
            if (!published) {
                throw CancellationException("P2P stream start was cancelled")
            }
            logPhase(startedAtMs, "route_ready", "generation=$generation")
            stream.url
        } catch (cancellation: CancellationException) {
            diagnostics.w { "start phase=${phase.get()} cancelled elapsedMs=${elapsedSince(startedAtMs)}" }
            withContext(NonCancellable) {
                cleanupFailedStart(generation, activeEngine, preparedStream, attached, P2pStreamingState.Idle)
            }
            throw cancellation
        } catch (error: Exception) {
            diagnostics.e(error) {
                "start phase=${phase.get()} failed elapsedMs=${elapsedSince(startedAtMs)} " +
                    "error=${diagnosticMessage(error.message)}"
            }
            val terminalState = P2pStreamingState.Error(error.message ?: localizedP2pUnknownTorrentError())
            withContext(NonCancellable) {
                cleanupFailedStart(generation, activeEngine, preparedStream, attached, terminalState)
            }
            throw error
        } finally {
            startupStatsJob?.cancel()
        }
    }

    private fun scheduleStop(shutdownEngine: Boolean) {
        val detached = detachActiveStream()
        val previousCleanup = cleanupJob
        cleanupJob = scope.launch {
            previousCleanup?.join()
            cleanupDetachedStream(detached, shutdownEngine)
        }
    }

    private suspend fun stopStreamNow(shutdownEngine: Boolean) {
        cleanupJob?.join()
        cleanupDetachedStream(detachActiveStream(), shutdownEngine)
    }

    private fun detachActiveStream(): DetachedStream {
        val detached: Pair<DetachedStream, Job?> = synchronized(lifecycleLock) {
            streamGeneration += 1
            val value = DetachedStream(engine = engine, streamId = currentStreamId)
            val job = statsJob
            currentTorrentId = null
            currentStreamId = null
            statsJob = null
            _state.value = P2pStreamingState.Idle
            value to job
        }
        detached.second?.cancel()
        return detached.first
    }

    private fun detachGenerationIfCurrent(
        generation: Long,
        terminalState: P2pStreamingState,
    ): DetachedStream? {
        val detached: Pair<DetachedStream, Job?>? = synchronized(lifecycleLock) {
            if (streamGeneration != generation) return@synchronized null
            streamGeneration += 1
            val value = DetachedStream(engine = engine, streamId = currentStreamId)
            val job = statsJob
            currentTorrentId = null
            currentStreamId = null
            statsJob = null
            _state.value = terminalState
            value to job
        }
        detached?.second?.cancel()
        return detached?.first
    }

    private suspend fun cleanupDetachedStream(detached: DetachedStream, shutdownEngine: Boolean) {
        detached.streamId?.let { streamId -> stopPreparedStream(detached.engine, streamId) }
        if (shutdownEngine) {
            closeEngine(detached.engine)
        }
    }

    private suspend fun cleanupFailedStart(
        generation: Long,
        activeEngine: NuvioEngine?,
        preparedStream: NuvioStream?,
        attached: Boolean,
        terminalState: P2pStreamingState,
    ) {
        if (attached) {
            detachGenerationIfCurrent(generation, terminalState)?.let { detached ->
                cleanupDetachedStream(detached, shutdownEngine = false)
            }
        } else {
            preparedStream?.let { stream -> stopPreparedStream(activeEngine, stream.id) }
            detachGenerationIfCurrent(generation, terminalState)
        }
    }

    private suspend fun stopPreparedStream(activeEngine: NuvioEngine?, streamId: String) {
        try {
            activeEngine?.stopStream(streamId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "Error stopping Nuvio Engine stream route" }
        }
    }

    private suspend fun ensureEngine(): NuvioEngine {
        P2pSettingsRepository.ensureLoaded()
        val settings = P2pSettingsRepository.uiState.value
        val configurationKey = EngineConfigurationKey(
            uploadEnabled = settings.enableUpload,
            torrentProfile = settings.torrentProfile,
            diskCacheCapacityBytes = settings.cacheSize.bytes,
        )
        engine?.takeIf { engineConfigurationKey == configurationKey }?.let { return it }

        closeEngine(engine)
        currentCoroutineContext().ensureActive()
        removeLegacyTorrServerData()
        val stateDirectory = DesktopStorage.rootDir.resolve("nuvio-engine/state").toFile()
        val cacheDirectory = DesktopStorage.cacheDir.resolve("nuvio-engine").toFile()
        check(stateDirectory.mkdirs() || stateDirectory.isDirectory) {
            "Could not create the Nuvio Engine state directory"
        }
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "Could not create the Nuvio Engine cache directory"
        }
        migrateNestedPayloadDirectory(cacheDirectory)
        val activeRuntime = runtime
        return activeRuntime.create(
            buildNuvioEngineConfig(
                stateDirectory = stateDirectory,
                cacheDirectory = cacheDirectory,
                uploadEnabled = configurationKey.uploadEnabled,
                torrentProfile = configurationKey.torrentProfile,
                diskCacheCapacityBytes = configurationKey.diskCacheCapacityBytes,
            ),
        ).also { created ->
            engine = created
            engineConfigurationKey = configurationKey
            observeEngineEvents(created)
            log.i { "Using Nuvio Engine ${activeRuntime.version} (${activeRuntime.protocolBackendVersion})" }
        }
    }

    private suspend fun closeEngine(target: NuvioEngine?) {
        if (target == null) return
        if (engine === target) {
            engineEventsJob?.cancel()
            engineEventsJob = null
            engine = null
            engineConfigurationKey = null
            knownTorrentIds.clear()
        }
        withContext(NonCancellable) {
            try {
                target.shutdown()
            } catch (error: Exception) {
                log.w(error) { "Error shutting down Nuvio Engine" }
            }
        }
    }

    private fun removeLegacyTorrServerData() {
        val legacyDirectory = DesktopStorage.rootDir.resolve("torrserver").toFile()
        if (legacyDirectory.exists()) {
            runCatching { legacyDirectory.deleteRecursively() }
        }
    }

    private fun observeEngineEvents(activeEngine: NuvioEngine) {
        engineEventsJob?.cancel()
        engineEventsJob = scope.launch {
            launch {
                activeEngine.stats.collect { stats ->
                    if (engine === activeEngine) {
                        updateCacheState(stats.diskCacheUsedBytes, stats.diskCacheProtectedBytes)
                    }
                }
            }
            activeEngine.events.collect { event ->
                diagnostics.i {
                    "event type=${event.type} requestId=${event.requestId} torrent=${diagnosticId(event.torrentId)} " +
                        "stream=${diagnosticId(event.streamId)} message=${diagnosticMessage(event.message)}"
                }
                if (engine !== activeEngine) return@collect
                when (event.type) {
                    NuvioEventType.TorrentError -> synchronized(lifecycleLock) {
                        if (engine !== activeEngine) return@synchronized
                        val error = unexpectedTorrentError(
                            requestId = event.requestId,
                            eventTorrentId = event.torrentId,
                            currentTorrentId = currentTorrentId,
                            message = event.message,
                            fallbackMessage = localizedP2pUnknownTorrentError(),
                        ) ?: return@synchronized
                        streamGeneration += 1
                        statsJob?.cancel()
                        statsJob = null
                        _state.value = error
                    }
                    NuvioEventType.StreamStopped -> synchronized(lifecycleLock) {
                        if (engine !== activeEngine) return@synchronized
                        val error = unexpectedStreamStopError(
                            requestId = event.requestId,
                            eventStreamId = event.streamId,
                            currentStreamId = currentStreamId,
                            message = event.message,
                            fallbackMessage = localizedP2pUnknownTorrentError(),
                        ) ?: return@synchronized
                        streamGeneration += 1
                        currentTorrentId = null
                        currentStreamId = null
                        statsJob?.cancel()
                        statsJob = null
                        _state.value = error
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun startStatsPolling(
        activeEngine: NuvioEngine,
        stream: NuvioStream,
        generation: Long,
        payloadDownloadBaseline: Long,
    ) {
        statsJob?.cancel()
        statsJob = scope.launch {
            var nextSampleAtMs = 0L
            while (isActive) {
                if (!isCurrentGeneration(generation)) return@launch
                if (_state.value is P2pStreamingState.Streaming) {
                    val route = try {
                        activeEngine.currentStreamStats(stream.id)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        log.w(error) { "Error sampling Nuvio Engine stream progress" }
                        null
                    }
                    val aggregate = activeEngine.stats.value
                    val now = nowMs()
                    if (now >= nextSampleAtMs) {
                        nextSampleAtMs = now + SAMPLE_INTERVAL_MS
                        diagnostics.i {
                            "sample stream=${diagnosticId(stream.id)} ${aggregate.diagnosticSummary()} " +
                                route.diagnosticSummary()
                        }
                    }
                    updateStreamingIfCurrent(generation) { latest ->
                        latest.copy(
                            downloadSpeed = aggregate.downloadRateBytesPerSecond,
                            uploadSpeed = aggregate.uploadRateBytesPerSecond,
                            peers = aggregate.connectedPeers,
                            seeds = aggregate.connectedSeeds,
                            bufferProgress = route?.bufferProgress ?: latest.bufferProgress,
                            totalProgress = route?.fileProgress ?: latest.totalProgress,
                            downloadedBytes = (aggregate.totalPayloadDownloadBytes - payloadDownloadBaseline)
                                .coerceAtLeast(0L),
                            verifiedBytes = route?.verifiedFileBytes ?: latest.verifiedBytes,
                            deliveredBytes = route?.deliveredBytes ?: latest.deliveredBytes,
                        )
                    }
                }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private fun startStartupStatsPolling(
        activeEngine: NuvioEngine,
        generation: Long,
        phase: AtomicReference<String>,
    ): Job = scope.launch {
        while (isActive) {
            val aggregate = activeEngine.stats.value
            updateConnectingIfCurrent(
                generation = generation,
                state = P2pStreamingState.Connecting(
                    phase = phase.get(),
                    downloadSpeed = aggregate.downloadRateBytesPerSecond,
                    uploadSpeed = aggregate.uploadRateBytesPerSecond,
                    peers = aggregate.connectedPeers,
                    seeds = aggregate.connectedSeeds,
                ),
            )
            diagnostics.i { "startupSample phase=${phase.get()} ${aggregate.diagnosticSummary()}" }
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    private fun updateCacheState(usedBytes: Long, protectedBytes: Long) {
        _cacheState.value = _cacheState.value.copy(
            usedBytes = usedBytes,
            protectedBytes = protectedBytes,
            hasMeasurement = true,
        )
    }

    private fun beginStreamGeneration(): Long = synchronized(lifecycleLock) {
        streamGeneration += 1
        _state.value = P2pStreamingState.Connecting()
        streamGeneration
    }

    private fun updateConnectingIfCurrent(generation: Long, state: P2pStreamingState.Connecting) =
        synchronized(lifecycleLock) {
            if (streamGeneration == generation && _state.value is P2pStreamingState.Connecting) {
                _state.value = state
            }
        }

    private fun attachStreamIfCurrent(
        generation: Long,
        torrentId: String,
        streamId: String,
    ): Boolean = synchronized(lifecycleLock) {
        if (streamGeneration != generation) return@synchronized false
        currentTorrentId = torrentId
        currentStreamId = streamId
        true
    }

    private fun publishStreamingIfCurrent(
        generation: Long,
        state: P2pStreamingState.Streaming,
    ): Boolean = synchronized(lifecycleLock) {
        if (streamGeneration != generation || currentStreamId == null) return@synchronized false
        _state.value = state
        true
    }

    private fun updateStreamingIfCurrent(
        generation: Long,
        update: (P2pStreamingState.Streaming) -> P2pStreamingState.Streaming,
    ) = synchronized(lifecycleLock) {
        if (streamGeneration != generation) return@synchronized
        val current = _state.value as? P2pStreamingState.Streaming ?: return@synchronized
        _state.value = update(current)
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        synchronized(lifecycleLock) { streamGeneration == generation }

    private fun ensureCurrentGeneration(generation: Long) {
        if (!isCurrentGeneration(generation)) {
            throw CancellationException("P2P stream start was cancelled")
        }
    }

    private fun logPhase(startedAtMs: Long, phase: String, detail: String) {
        diagnostics.i { "start phase=$phase complete elapsedMs=${elapsedSince(startedAtMs)} $detail" }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private fun elapsedSince(startedAtMs: Long): Long = (nowMs() - startedAtMs).coerceAtLeast(0L)

    private fun diagnosticId(value: String?): String =
        value?.trim()?.take(12)?.ifBlank { "none" } ?: "none"

    private fun diagnosticMessage(value: String?): String =
        value?.replace('\n', ' ')?.replace('\r', ' ')?.take(160) ?: "none"

    private fun NuvioEngineStats.diagnosticSummary(): String =
        "http=$activeHttpRequests pendingReads=$pendingPieceReads " +
            "peers=$connectedPeers seeds=$connectedSeeds known=$knownPeers " +
            "downloading=$downloadingPeers downBps=$downloadRateBytesPerSecond " +
            "upBps=$uploadRateBytesPerSecond payloadDown=$totalPayloadDownloadBytes " +
            "activeTorrents=$activeTorrents activeStreams=$activeStreams " +
            "diskUsed=$diskCacheUsedBytes diskProtected=$diskCacheProtectedBytes"

    private fun NuvioStreamStats?.diagnosticSummary(): String =
        if (this == null) {
            "route=none"
        } else {
            "contiguous=$contiguousReadyBytes verified=$verifiedFileBytes delivered=$deliveredBytes " +
                "demands=$activeDemands scheduledPieces=$scheduledPieces blockingPieces=$blockingPieces"
        }

    private val DEFAULT_TRACKERS = listOf(
        "udp://zer0day.ch:1337/announce",
        "udp://tracker.publictracker.xyz:6969/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce",
        "http://tracker.renfei.net:8080/announce",
        "udp://udp.tracker.projectk.org:23333/announce",
        "udp://tracker.tryhackx.org:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://tracker.startwork.cv:1337/announce",
        "udp://tracker.qu.ax:6969/announce",
        "udp://tracker.plx.im:6969/announce",
        "udp://tracker.nyaa.vc:6969/announce",
        "udp://tracker.iperson.xyz:6969/announce",
        "udp://tracker.gmi.gd:6969/announce",
        "udp://tracker.fnix.net:6969/announce",
        "udp://tracker.flatuslifir.is:6969/announce",
        "udp://tracker.ducks.party:1984/announce",
        "udp://tracker.bluefrog.pw:2710/announce",
    )
}
