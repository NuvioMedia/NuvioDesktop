package com.nuvio.app.features.p2p

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.DesktopBufferPreset
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.NuvioEventType
import com.nuvio.engine.NuvioStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object NuvioEngineP2pBackend : DesktopP2pBackend {
    private val log = Logger.withTag("NuvioEngineP2pBackend")

    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    override val state: StateFlow<P2pStreamingState> = _state.asStateFlow()

    private val _cacheState = MutableStateFlow(P2pCacheUiState())
    override val cacheState: StateFlow<P2pCacheUiState> = _cacheState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private val startMutex = Mutex()

    private var statsJob: Job? = null
    private var cleanupJob: Job? = null
    private var engineEventsJob: Job? = null

    private var streamGeneration: Long = 0L
    @Volatile
    private var currentTorrentId: String? = null
    @Volatile
    private var currentStreamId: String? = null
    @Volatile
    private var engine: NuvioEngine? = null
    private var engineConfigurationKey: EngineConfigurationKey? = null

    private val knownTorrentIds = LinkedHashSet<String>()
    private var requestSequence: Long = 0L

    private val DEFAULT_TRACKERS: List<String> = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker-udp.gbitt.info:80/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://tracker.qu.ax:6969/announce",
        "udp://opentracker.io:6969/announce",
        "udp://p4p.arenabg.com:1337/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://wepzone.net:6969/announce",
        "udp://bt.bontal.net:6969/announce"
    )

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            runBlocking {
                stopStreamNow(shutdownEngine = true)
            }
        }.apply {
            name = "nuvio-engine-shutdown"
        })
        scope.launch(Dispatchers.IO) {
            measureDiskCache()
        }
    }

    override suspend fun startStream(request: P2pStreamRequest): String = withContext(Dispatchers.IO) {
        startMutex.withLock {
            startStreamLocked(request)
        }
    }

    override suspend fun clearCache(): P2pCacheClearResult = withContext(Dispatchers.IO) {
        startMutex.withLock {
            check(_state.value !is P2pStreamingState.Streaming && _state.value !is P2pStreamingState.Connecting) {
                "Torrent cache cannot be cleared during active playback"
            }
            _cacheState.value = _cacheState.value.copy(isClearing = true)
            try {
                val root = DesktopStorage.rootDir.resolve("nuvio-engine").toFile()
                val cacheDirectory = File(root, "payload")
                val diskBefore = if (cacheDirectory.exists()) {
                    cacheDirectory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                } else 0L

                val activeEngine = engine
                if (activeEngine != null) {
                    runCatching { activeEngine.reclaimDiskCache(0L) }
                    delay(300L)
                }

                var reclaimedFilesBytes = 0L
                if (cacheDirectory.exists()) {
                    cacheDirectory.listFiles()?.forEach { file ->
                        val len = if (file.isDirectory) {
                            file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        } else file.length()
                        if (file.deleteRecursively()) {
                            reclaimedFilesBytes += len
                        }
                    }
                }

                val diskAfter = if (cacheDirectory.exists()) {
                    cacheDirectory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                } else 0L

                val activeUsed = activeEngine?.stats?.value?.diskCacheUsedBytes ?: 0L
                val activeProtected = activeEngine?.stats?.value?.diskCacheProtectedBytes ?: 0L
                val finalRemaining = maxOf(diskAfter, activeUsed)
                updateCacheState(finalRemaining, activeProtected)

                val reclaimed = maxOf(diskBefore - diskAfter, reclaimedFilesBytes)
                P2pCacheClearResult(
                    reclaimedBytes = reclaimed,
                    remainingBytes = finalRemaining,
                    protectedBytes = activeProtected
                )
            } finally {
                _cacheState.value = _cacheState.value.copy(isClearing = false)
            }
        }
    }

    private fun measureDiskCache() {
        val root = DesktopStorage.rootDir.resolve("nuvio-engine").toFile()
        val cacheDirectory = File(root, "payload")
        val diskUsed = if (cacheDirectory.exists()) {
            cacheDirectory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else 0L
        val activeUsed = engine?.stats?.value?.diskCacheUsedBytes ?: 0L
        val activeProtected = engine?.stats?.value?.diskCacheProtectedBytes ?: 0L
        updateCacheState(maxOf(diskUsed, activeUsed), activeProtected)
    }

    private suspend fun startStreamLocked(request: P2pStreamRequest): String {
        val sequence = nextRequestSequence()
        val startedAt = System.nanoTime()
        val phase = AtomicReference("stop_previous")
        log.i {
            "P2P startup begin: request=$sequence hash=${diagnosticId(request.infoHash)} requestedFile=${request.fileIdx ?: "auto"} filenameHint=${!request.filename.isNullOrBlank()} trackers=${request.trackers.size}"
        }
        stopStreamNow(shutdownEngine = false)
        val generation = beginStreamGeneration()

        var activeEngine: NuvioEngine? = null
        var preparedStream: NuvioStream? = null
        var attached = false
        var startupStatsJob: Job? = null

        try {
            phase.set("starting_engine")
            val magnetUri = buildP2pMagnetUri(request.infoHash, (DEFAULT_TRACKERS + request.trackers).distinct())
            val resolvedEngine = ensureEngine()
            activeEngine = resolvedEngine
            val payloadDownloadBaseline = resolvedEngine.stats.value.totalPayloadDownloadBytes
            ensureCurrentGeneration(generation)
            val engineReadyElapsed = elapsedMillis(startedAt)
            startupStatsJob = startStartupStatsPolling(resolvedEngine, generation, phase, sequence, startedAt)

            phase.set("add_magnet")
            val canonicalHash = canonicalP2pInfoHash(request.infoHash)
            val isKnown = knownTorrentIds.contains(canonicalHash)
            val torrentId = if (isKnown) {
                canonicalHash
            } else {
                addMagnetWithWatchdog(resolvedEngine, magnetUri, canonicalHash, sequence, startedAt).also {
                    knownTorrentIds.add(it)
                }
            }

            ensureCurrentGeneration(generation)
            val metadataElapsed = elapsedMillis(startedAt)

            phase.set("prepare_stream")
            val stream = resolvedEngine.prepareStream(
                torrentId = torrentId,
                fileIndex = request.fileIdx,
                filenameHint = request.filename
            )
            preparedStream = stream

            currentCoroutineContext().ensureActive()
            phase.set("attach_route")
            if (!attachStreamIfCurrent(generation, torrentId, stream.id)) {
                withContext(NonCancellable) {
                    stopPreparedStream(resolvedEngine, stream.id)
                }
                throw CancellationException("P2P stream start was cancelled")
            }
            attached = true

            val totalElapsed = elapsedMillis(startedAt)
            log.i {
                "P2P startup ready: request=$sequence hash=${diagnosticId(torrentId)} file=${stream.fileIndex} fileBytes=${stream.fileSize} total=${totalElapsed}ms engine=${engineReadyElapsed}ms metadata=${metadataElapsed - engineReadyElapsed}ms${if (isKnown) " (warm)" else ""} prepare=${totalElapsed - metadataElapsed}ms"
            }

            startStatsPolling(resolvedEngine, stream, generation, sequence, startedAt, payloadDownloadBaseline)

            val currentStats = resolvedEngine.stats.value
            val initialStreaming = P2pStreamingState.Streaming(
                localUrl = stream.url,
                downloadSpeed = currentStats.downloadRateBytesPerSecond,
                uploadSpeed = currentStats.uploadRateBytesPerSecond,
                peers = currentStats.connectedPeers,
                seeds = currentStats.connectedSeeds,
                bufferProgress = 0f,
                totalProgress = 0f,
                downloadedBytes = (currentStats.totalPayloadDownloadBytes - payloadDownloadBaseline).coerceAtLeast(0L),
                verifiedBytes = 0L,
                deliveredBytes = 0L
            )
            if (!publishStreamingIfCurrent(generation, initialStreaming)) {
                throw CancellationException("P2P stream start was cancelled")
            }
            log.d { "Nuvio Engine stream ready: ${stream.url}" }
            return stream.url
        } catch (cancellation: CancellationException) {
            log.i { "P2P startup cancelled: request=$sequence phase=${phase.get()} after=${elapsedMillis(startedAt)}ms" }
            withContext(NonCancellable) {
                cleanupFailedStart(generation, activeEngine, preparedStream, attached, P2pStreamingState.Idle)
            }
            throw cancellation
        } catch (error: Exception) {
            log.e(error) { "P2P startup failed: request=$sequence phase=${phase.get()} after=${elapsedMillis(startedAt)}ms" }
            val terminalError = P2pStreamingState.Error(error.message ?: "Unknown torrent error")
            withContext(NonCancellable) {
                cleanupFailedStart(generation, activeEngine, preparedStream, attached, terminalError)
            }
            throw error
        } finally {
            startupStatsJob?.cancel()
        }
    }

    private suspend fun addMagnetWithWatchdog(
        activeEngine: NuvioEngine,
        magnetUri: String,
        canonicalHash: String,
        sequence: Long,
        startedAt: Long
    ): String = coroutineScope {
        val baseline = activeEngine.stats.value
        val add = async {
            activeEngine.addMagnet(magnetUri)
        }
        val watchdog = async {
            val waitStartedAt = System.nanoTime()
            var lastKnown = 0L
            var lastProgressAt = waitStartedAt
            while (true) {
                delay(1000L)
                val stats = activeEngine.stats.value
                val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStartedAt)
                val known = (stats.knownPeers - baseline.knownPeers).coerceAtLeast(0)
                val connected = (stats.connectedPeers - baseline.connectedPeers).coerceAtLeast(0)
                val downloaded = stats.totalPayloadDownloadBytes - baseline.totalPayloadDownloadBytes
                val moving = known > lastKnown || connected > 0 || downloaded > 0L
                if (moving) {
                    lastProgressAt = System.nanoTime()
                }
                lastKnown = maxOf(lastKnown, known.toLong())
                val stalledFor = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastProgressAt)
                val verdict = metadataWaitVerdict(waited, stalledFor, lastKnown)
                if (verdict != null) {
                    return@async MetadataGiveUp(
                        message = verdict,
                        detail = "waited=${waited}ms stalled=${stalledFor}ms known=$lastKnown connected=$connected trackerReplies=${stats.trackerReplyEvents - baseline.trackerReplyEvents} trackerPeers=${stats.trackerPeersReturned - baseline.trackerPeersReturned} dhtPeers=${stats.dhtPeersReturned - baseline.dhtPeersReturned} connects=${stats.peerConnectEvents - baseline.peerConnectEvents} connectFailures=${stats.peerDisconnectConnectFailures - baseline.peerDisconnectConnectFailures}"
                    )
                }
            }
        }

        val result = select<Any> {
            add.onAwait { it }
            watchdog.onAwait { it }
        }

        if (result is String) {
            watchdog.cancel()
            result
        } else if (result is MetadataGiveUp) {
            add.cancel()
            log.w {
                "P2P metadata give-up: request=$sequence after=${elapsedMillis(startedAt)}ms ${result.detail}"
            }
            withContext(NonCancellable) {
                try {
                    activeEngine.removeTorrent(canonicalHash)
                } catch (error: Exception) {
                    log.d(error) { "Could not remove the unresolved torrent ${diagnosticId(canonicalHash)}" }
                }
            }
            throw P2pStreamingException(result.message)
        } else {
            error("unexpected watchdog result $result")
        }
    }

    override fun stopStream() {
        scheduleStop(shutdownEngine = false)
    }

    override fun shutdown() {
        scheduleStop(shutdownEngine = true)
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
        val detached = detachActiveStream()
        cleanupDetachedStream(detached, shutdownEngine)
    }

    private fun detachActiveStream(): DetachedStream {
        val (detached, job) = synchronized(lifecycleLock) {
            streamGeneration++
            val value = DetachedStream(engine, currentStreamId)
            val job = statsJob
            currentTorrentId = null
            currentStreamId = null
            statsJob = null
            _state.value = P2pStreamingState.Idle
            value to job
        }
        job?.cancel()
        return detached
    }

    private fun detachGenerationIfCurrent(generation: Long, terminalState: P2pStreamingState?): DetachedStream? {
        val detached = synchronized(lifecycleLock) {
            if (streamGeneration != generation) {
                null
            } else {
                streamGeneration++
                val value = DetachedStream(engine, currentStreamId)
                val job = statsJob
                currentTorrentId = null
                currentStreamId = null
                statsJob = null
                if (terminalState != null) {
                    _state.value = terminalState
                }
                value to job
            }
        }
        detached?.second?.cancel()
        return detached?.first
    }

    private suspend fun cleanupDetachedStream(detached: DetachedStream, shutdownEngine: Boolean) {
        detached.streamId?.let { streamId ->
            stopPreparedStream(detached.engine, streamId)
        }
        if (shutdownEngine) {
            closeEngine(detached.engine)
        }
    }

    private suspend fun cleanupFailedStart(
        generation: Long,
        activeEngine: NuvioEngine?,
        preparedStream: NuvioStream?,
        attached: Boolean,
        terminalState: P2pStreamingState?
    ) {
        if (attached) {
            val detached = detachGenerationIfCurrent(generation, terminalState)
            if (detached != null) {
                cleanupDetachedStream(detached, shutdownEngine = false)
            }
        } else {
            if (preparedStream != null) {
                stopPreparedStream(activeEngine, preparedStream.id)
            }
            detachGenerationIfCurrent(generation, terminalState)
        }
    }

    private suspend fun stopPreparedStream(activeEngine: NuvioEngine?, streamId: String) {
        if (activeEngine == null) return
        try {
            activeEngine.stopStream(streamId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "Error stopping Nuvio Engine stream route" }
        }
    }

    private suspend fun ensureEngine(): NuvioEngine {
        P2pSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.ensureLoaded()
        val settings = P2pSettingsRepository.uiState.value
        val configurationKey = EngineConfigurationKey(
            uploadEnabled = settings.enableUpload,
            torrentProfile = settings.torrentProfile,
            diskCacheCapacityBytes = settings.cacheSize.bytes,
            windowBytes = nuvioEngineWindowBytes(DesktopBufferPreset.Balanced)
        )

        engine?.takeIf { engineConfigurationKey == configurationKey }?.let {
            return it
        }

        val startedAt = System.nanoTime()
        log.i { "Nuvio Engine create: $configurationKey replacing=${engine != null}" }
        closeEngine(engine)
        currentCoroutineContext().ensureActive()

        val root = DesktopStorage.rootDir.resolve("nuvio-engine").toFile()
        val stateDirectory = File(root, "state")
        val cacheDirectory = File(root, "payload")
        check(stateDirectory.mkdirs() || stateDirectory.isDirectory) {
            "Could not create the Nuvio Engine state directory"
        }
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "Could not create the Nuvio Engine cache directory"
        }

        val created = NuvioEngine.create(
            buildNuvioEngineConfig(
                stateDirectory = stateDirectory,
                cacheDirectory = cacheDirectory,
                uploadEnabled = configurationKey.uploadEnabled,
                torrentProfile = configurationKey.torrentProfile,
                diskCacheCapacityBytes = configurationKey.diskCacheCapacityBytes,
                windowBytes = configurationKey.windowBytes
            )
        ).also {
            engine = it
            engineConfigurationKey = configurationKey
            observeEngineEvents(it)
            log.i {
                "Using Nuvio Engine ${NuvioEngine.version} (${NuvioEngine.protocolBackendVersion}) after ${elapsedMillis(startedAt)}ms"
            }
        }
        return created
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

    private fun observeEngineEvents(activeEngine: NuvioEngine) {
        engineEventsJob?.cancel()
        engineEventsJob = scope.launch {
            activeEngine.events.collect { event ->
                log.d {
                    "engine event type=${event.type} requestId=${event.requestId} dropped=${event.droppedEvents} torrent=${diagnosticId(event.torrentId)} stream=${diagnosticId(event.streamId)} message=${diagnosticMessage(event.message)}"
                }
                if (engine !== activeEngine) return@collect

                when (event.type) {
                    NuvioEventType.TorrentError -> {
                        synchronized(lifecycleLock) {
                            if (engine === activeEngine) {
                                val error = unexpectedTorrentError(
                                    requestId = event.requestId,
                                    eventTorrentId = event.torrentId,
                                    currentTorrentId = currentTorrentId,
                                    message = event.message,
                                    fallbackMessage = "Unknown torrent error"
                                )
                                if (error != null) {
                                    streamGeneration++
                                    statsJob?.cancel()
                                    statsJob = null
                                    _state.value = error
                                }
                            }
                        }
                    }
                    NuvioEventType.StreamStopped -> {
                        if (event.requestId != 0L) return@collect
                        synchronized(lifecycleLock) {
                            if (engine === activeEngine) {
                                val error = unexpectedStreamStopError(
                                    requestId = event.requestId,
                                    eventStreamId = event.streamId,
                                    currentStreamId = currentStreamId,
                                    message = event.message,
                                    fallbackMessage = "Unknown torrent error"
                                )
                                if (error != null) {
                                    streamGeneration++
                                    currentTorrentId = null
                                    currentStreamId = null
                                    statsJob?.cancel()
                                    statsJob = null
                                    _state.value = error
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun updateCacheState(usedBytes: Long, protectedBytes: Long) {
        _cacheState.value = _cacheState.value.copy(
            usedBytes = usedBytes,
            protectedBytes = protectedBytes,
            isClearing = false,
            hasMeasurement = true
        )
    }

    private fun startStatsPolling(
        activeEngine: NuvioEngine,
        stream: NuvioStream,
        generation: Long,
        sequence: Long,
        startedAt: Long,
        payloadDownloadBaseline: Long
    ) {
        statsJob?.cancel()
        statsJob = scope.launch {
            var nextSampleAtMs = 0L
            var loggedFirstTransfer = false

            while (isActive) {
                if (!isCurrentGeneration(generation)) {
                    return@launch
                }
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
                    updateCacheState(aggregate.diskCacheUsedBytes, aggregate.diskCacheProtectedBytes)
                    val downloaded = (aggregate.totalPayloadDownloadBytes - payloadDownloadBaseline).coerceAtLeast(0L)

                    if (!loggedFirstTransfer && downloaded > 0L) {
                        loggedFirstTransfer = true
                        log.i {
                            "P2P first transfer: request=$sequence after=${elapsedMillis(startedAt)}ms peers=${aggregate.connectedPeers} seeds=${aggregate.connectedSeeds} speed=${aggregate.downloadRateBytesPerSecond}B/s"
                        }
                    }

                    val nowMs = elapsedMillis(startedAt)
                    if (nowMs >= nextSampleAtMs) {
                        nextSampleAtMs = nowMs + 5000L
                        log.d {
                            buildString {
                                append("P2P sample: request=").append(sequence)
                                    .append(" after=").append(nowMs).append("ms")
                                    .append(" peers=").append(aggregate.connectedPeers)
                                    .append(" seeds=").append(aggregate.connectedSeeds)
                                    .append(" known=").append(aggregate.knownPeers)
                                    .append(" downloading=").append(aggregate.downloadingPeers)
                                    .append(" unchoked=").append(aggregate.unchokedPeers)
                                    .append(" downBps=").append(aggregate.downloadRateBytesPerSecond)
                                    .append(" upBps=").append(aggregate.uploadRateBytesPerSecond)
                                    .append(" downloaded=").append(downloaded)
                                    .append(" contiguous=").append(route?.contiguousReadyBytes ?: -1L)
                                    .append(" delivered=").append(route?.deliveredBytes ?: -1L)
                                    .append(" blockingPieces=").append(route?.blockingPieces ?: -1)
                                    .append(" blockingPiece=").append(route?.primaryBlockingPiece ?: -1)
                                    .append(" lastReady=").append(route?.lastReadyPiece ?: -1)
                                    .append(" demand=").append(route?.primaryDemandStart ?: -1L).append('-').append(route?.primaryDemandEnd ?: -1L)
                                    .append(" secondary=").append(route?.secondaryDemandStart ?: -1L).append('-').append(route?.secondaryDemandEnd ?: -1L)
                                    .append(" scheduled=").append(route?.scheduledPieces ?: -1)
                                    .append(" demands=").append(route?.activeDemands ?: -1)
                                    .append(" trackerPeers=").append(aggregate.trackerPeersReturned)
                                    .append(" dhtPeers=").append(aggregate.dhtPeersReturned)
                                    .append(" timedOutBlocks=").append(aggregate.timedOutBlockRequests)
                                    .append(" http=").append(aggregate.activeHttpRequests)
                                    .append(" diskUsed=").append(aggregate.diskCacheUsedBytes)
                                    .append(" overBudget=").append(aggregate.diskCacheOverBudget)
                            }
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
                            downloadedBytes = downloaded,
                            verifiedBytes = route?.verifiedFileBytes ?: latest.verifiedBytes,
                            deliveredBytes = route?.deliveredBytes ?: latest.deliveredBytes
                        )
                    }
                }
                delay(250L)
            }
        }
    }

    private fun startStartupStatsPolling(
        activeEngine: NuvioEngine,
        generation: Long,
        phase: AtomicReference<String>,
        sequence: Long,
        startedAt: Long
    ): Job = scope.launch {
        while (isActive) {
            val aggregate = activeEngine.stats.value
            updateConnectingIfCurrent(
                generation = generation,
                phase = phase.get(),
                downloadSpeed = aggregate.downloadRateBytesPerSecond,
                uploadSpeed = aggregate.uploadRateBytesPerSecond,
                peers = aggregate.connectedPeers,
                seeds = aggregate.connectedSeeds
            )
            log.d {
                buildString {
                    append("P2P startup sample: request=").append(sequence)
                        .append(" phase=").append(phase.get())
                        .append(" after=").append(elapsedMillis(startedAt)).append("ms")
                        .append(" peers=").append(aggregate.connectedPeers)
                        .append(" seeds=").append(aggregate.connectedSeeds)
                        .append(" known=").append(aggregate.knownPeers)
                        .append(" connecting=").append(aggregate.connectingPeers)
                        .append(" handshaking=").append(aggregate.handshakingPeers)
                        .append(" trackerReplies=").append(aggregate.trackerReplyEvents)
                        .append(" trackerErrors=").append(aggregate.trackerErrorEvents)
                        .append(" trackerPeers=").append(aggregate.trackerPeersReturned)
                        .append(" dhtReplies=").append(aggregate.dhtReplyEvents)
                        .append(" dhtPeers=").append(aggregate.dhtPeersReturned)
                        .append(" connects=").append(aggregate.peerConnectEvents)
                        .append(" connectFailures=").append(aggregate.peerDisconnectConnectFailures)
                        .append(" downBps=").append(aggregate.downloadRateBytesPerSecond)
                }
            }
            delay(1000L)
        }
    }

    private fun beginStreamGeneration(): Long = synchronized(lifecycleLock) {
        _state.value = P2pStreamingState.Connecting()
        ++streamGeneration
    }

    private fun updateConnectingIfCurrent(
        generation: Long,
        phase: String,
        downloadSpeed: Long,
        uploadSpeed: Long,
        peers: Int,
        seeds: Int
    ) = synchronized(lifecycleLock) {
        if (streamGeneration == generation && _state.value is P2pStreamingState.Connecting) {
            _state.value = P2pStreamingState.Connecting(
                phase = phase,
                downloadSpeed = downloadSpeed,
                uploadSpeed = uploadSpeed,
                peers = peers,
                seeds = seeds
            )
        }
    }

    private fun nextRequestSequence(): Long = synchronized(lifecycleLock) {
        ++requestSequence
    }

    private fun attachStreamIfCurrent(generation: Long, torrentId: String, streamId: String): Boolean = synchronized(lifecycleLock) {
        if (streamGeneration != generation) {
            false
        } else {
            currentTorrentId = torrentId
            currentStreamId = streamId
            true
        }
    }

    private fun publishStreamingIfCurrent(generation: Long, state: P2pStreamingState.Streaming): Boolean = synchronized(lifecycleLock) {
        if (streamGeneration != generation || currentStreamId == null) {
            false
        } else {
            _state.value = state
            true
        }
    }

    private fun updateStreamingIfCurrent(
        generation: Long,
        update: (P2pStreamingState.Streaming) -> P2pStreamingState.Streaming
    ) = synchronized(lifecycleLock) {
        if (streamGeneration == generation) {
            val current = _state.value as? P2pStreamingState.Streaming
            if (current != null) {
                _state.value = update(current)
            }
        }
    }

    private fun isCurrentGeneration(generation: Long): Boolean = synchronized(lifecycleLock) {
        streamGeneration == generation
    }

    private fun ensureCurrentGeneration(generation: Long) {
        if (!isCurrentGeneration(generation)) {
            throw CancellationException("P2P stream start was cancelled")
        }
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

    private fun diagnosticId(value: String?): String {
        val trimmed = value?.trim()?.take(12)
        return if (trimmed.isNullOrBlank()) "none" else trimmed
    }

    private fun diagnosticMessage(value: String?): String {
        return value?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.take(160)
            ?: "none"
    }

    private data class EngineConfigurationKey(
        val uploadEnabled: Boolean,
        val torrentProfile: P2pTorrentProfile,
        val diskCacheCapacityBytes: Long,
        val windowBytes: Long
    )

    private data class DetachedStream(
        val engine: NuvioEngine?,
        val streamId: String?
    )

    private data class MetadataGiveUp(
        val message: String,
        val detail: String
    )
}
