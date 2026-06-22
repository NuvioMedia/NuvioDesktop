package com.nuvio.app.features.p2p

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder

private val TAG = "P2pStreamingEngine"
private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "webm", "ts", "m4v", "mov", "wmv", "flv")

actual object P2pStreamingEngine {
    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    actual val state: StateFlow<P2pStreamingState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private var statsJob: Job? = null
    private var cleanupJob: Job? = null
    private var currentHash: String? = null
    private var streamGeneration = 0L
    private val binary = TorrServerBinary()
    private val api = TorrServerApi(binary)

    actual suspend fun startStream(request: P2pStreamRequest): String = withContext(Dispatchers.IO) {
        stopStreamNow(stopBinary = false)
        val generation = nextStreamGeneration()
        _state.value = P2pStreamingState.Connecting

        try {
            binary.start()
            ensureCurrentGeneration(generation)

            val magnetLink = buildMagnetUri(request.infoHash, request.trackers)
            println("$TAG: Starting stream: $magnetLink")

            val hash = api.addTorrent(magnetLink)
                ?: throw P2pStreamingException("Failed to add torrent")
            if (!attachTorrentIfCurrent(generation, hash)) {
                api.dropTorrent(hash)
                throw CancellationException("P2P stream start was cancelled")
            }

            val resolvedIdx = resolveFileIndex(
                hash = hash,
                requestedIdx = request.fileIdx,
                filename = request.filename,
            )
            ensureCurrentGeneration(generation)

            val streamUrl = api.getStreamUrl(magnetLink, resolvedIdx)
            println("$TAG: Stream URL: $streamUrl")

            startStatsPolling(hash, generation)

            ensureCurrentGeneration(generation)
            _state.value = P2pStreamingState.Streaming(
                localUrl = streamUrl,
                downloadSpeed = 0,
                uploadSpeed = 0,
                peers = 0,
                seeds = 0,
                bufferProgress = 0f,
                totalProgress = 0f,
            )

            streamUrl
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isCurrentGeneration(generation)) {
                _state.value = P2pStreamingState.Error(e.message ?: "Unknown P2P error")
            }
            throw e
        }
    }

    actual fun stopStream() {
        scheduleStop(stopBinary = false)
    }

    actual fun shutdown() {
        scheduleStop(stopBinary = true)
    }

    private fun scheduleStop(stopBinary: Boolean) {
        val hash = detachActiveStream()
        val previousCleanup = cleanupJob
        cleanupJob = scope.launch {
            previousCleanup?.join()
            cleanupDetachedStream(hash, stopBinary)
        }
    }

    private suspend fun stopStreamNow(stopBinary: Boolean) {
        cleanupJob?.join()
        val hash = detachActiveStream()
        cleanupDetachedStream(hash, stopBinary)
    }

    private fun detachActiveStream(): String? {
        val detached = synchronized(lifecycleLock) {
            streamGeneration += 1
            val hash = currentHash
            val job = statsJob
            currentHash = null
            statsJob = null
            hash to job
        }
        detached.second?.cancel()
        _state.value = P2pStreamingState.Idle
        return detached.first
    }

    private suspend fun cleanupDetachedStream(hash: String?, stopBinary: Boolean) {
        hash?.let {
            try {
                api.dropTorrent(it)
            } catch (e: Exception) {
                println("$TAG: Error dropping torrent: ${e.message}")
            }
        }

        if (stopBinary) {
            try {
                binary.stop()
            } catch (e: Exception) {
                println("$TAG: Error stopping TorrServer: ${e.message}")
            }
        }
    }

    private fun nextStreamGeneration(): Long =
        synchronized(lifecycleLock) {
            streamGeneration += 1
            streamGeneration
        }

    private fun attachTorrentIfCurrent(generation: Long, hash: String): Boolean =
        synchronized(lifecycleLock) {
            if (streamGeneration != generation) return@synchronized false
            currentHash = hash
            true
        }

    private fun isCurrentGeneration(generation: Long): Boolean =
        synchronized(lifecycleLock) { streamGeneration == generation }

    private fun ensureCurrentGeneration(generation: Long) {
        if (!isCurrentGeneration(generation)) {
            throw CancellationException("P2P stream start was cancelled")
        }
    }

    private fun buildMagnetUri(infoHash: String, extraTrackers: List<String>): String {
        val trackers = (DEFAULT_TRACKERS + extraTrackers).distinct()
        val trackerParams = trackers.joinToString("") { "&tr=$it" }
        return "magnet:?xt=urn:btih:$infoHash$trackerParams"
    }

    private suspend fun resolveFileIndex(hash: String, requestedIdx: Int?, filename: String?): Int {
        val deadline = System.currentTimeMillis() + 15_000L
        var files: List<TorrServerFile> = emptyList()

        while (System.currentTimeMillis() < deadline) {
            files = api.getTorrentStats(hash)?.files ?: emptyList()
            if (files.isNotEmpty()) break
            println("$TAG: Waiting for torrent metadata...")
            delay(1_000L)
        }

        if (files.isEmpty()) {
            println("$TAG: No files after metadata timeout, guessing index ${requestedIdx?.plus(1) ?: 1}")
            return requestedIdx?.plus(1) ?: 1
        }

        if (!filename.isNullOrBlank()) {
            val name = filename.trim()
            val exact = files.firstOrNull { file ->
                file.path.substringAfterLast('/').equals(name, ignoreCase = true)
            }
            if (exact != null) {
                println("$TAG: File resolved by exact filename match: ${exact.path} -> id=${exact.id}")
                return exact.id
            }

            val contains = files.firstOrNull { file ->
                file.path.contains(name, ignoreCase = true)
            }
            if (contains != null) {
                println("$TAG: File resolved by filename contains match: ${contains.path} -> id=${contains.id}")
                return contains.id
            }
        }

        if (requestedIdx != null) {
            val torrServerIndex = requestedIdx + 1
            if (files.any { it.id == torrServerIndex }) {
                println("$TAG: File resolved by ID offset: id=$torrServerIndex")
                return torrServerIndex
            }
        }

        if (requestedIdx != null && requestedIdx in files.indices) {
            val positionalFile = files[requestedIdx]
            println("$TAG: File resolved by positional index: [$requestedIdx] -> ${positionalFile.path} (id=${positionalFile.id})")
            return positionalFile.id
        }

        val videoFile = files
            .filter { file ->
                val ext = file.path.substringAfterLast('.', "").lowercase()
                ext in VIDEO_EXTENSIONS
            }
            .maxByOrNull { it.length }

        val result = videoFile?.id ?: files.maxByOrNull { it.length }?.id ?: 1
        println("$TAG: File resolved by largest video fallback: id=$result")
        return result
    }

    private fun startStatsPolling(hash: String, generation: Long) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                if (!isCurrentGeneration(generation)) return@launch
                try {
                    val stats = api.getTorrentStats(hash)
                    val currentState = _state.value
                    if (
                        stats != null &&
                        currentState is P2pStreamingState.Streaming &&
                        isCurrentGeneration(generation)
                    ) {
                        _state.value = currentState.copy(
                            downloadSpeed = stats.downloadSpeed,
                            uploadSpeed = stats.uploadSpeed,
                            peers = stats.peers,
                            seeds = stats.seeds,
                            preloadedBytes = stats.preloadedBytes,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("$TAG: Stats polling error: ${e.message}")
                }
                delay(1_000L)
            }
        }
    }

    private val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
    )

    private class TorrServerBinary {
        private var process: Process? = null
        private val httpClient = HttpClient(CIO) {
            engine {
                requestTimeout = 5_000
            }
            expectSuccess = false
        }

        val baseUrl: String get() = "http://127.0.0.1:$PORT"

        private fun binaryName(): String {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("win") -> "torrserver.exe"
                else -> "torrserver"
            }
        }

        private fun configDir(): File {
            val home = System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir")
            return File(home, ".nuvio/torrserver").also { it.mkdirs() }
        }

        suspend fun start() = withContext(Dispatchers.IO) {
            if (isRunning()) {
                println("$TAG: TorrServer already running")
                return@withContext
            }

            killOrphanedProcess()

            val binaryFile = resolveBinary()
            val configDir = configDir()
            val processBuilder = ProcessBuilder(
                binaryFile.absolutePath,
                "--port",
                PORT.toString(),
                "--path",
                configDir.absolutePath,
            )
            processBuilder.directory(configDir)
            processBuilder.redirectErrorStream(true)

            println("$TAG: Starting TorrServer on port $PORT from ${binaryFile.absolutePath}")
            process = processBuilder.start()

            val proc = process!!
            Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        println("$TAG: [server] $line")
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }

            val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (isRunning()) {
                    println("$TAG: TorrServer started successfully")
                    return@withContext
                }
                if (!isProcessAlive(process)) {
                    val exitCode = process?.exitValue() ?: -1
                    process = null
                    throw P2pStreamingException("TorrServer process died on startup (exit code $exitCode)")
                }
                delay(HEALTH_CHECK_INTERVAL_MS)
            }

            stop()
            throw P2pStreamingException("TorrServer failed to start within ${STARTUP_TIMEOUT_MS / 1000}s")
        }

        private fun resolveBinary(): File {
            val name = binaryName()

            val candidates = listOf(
                File("composeApp/build/native/torrserver/$name"),
                File("build/native/torrserver/$name"),
                File("native/torrserver/$name"),
            )
            candidates.firstOrNull { it.exists() }?.let { return it }

            val tempDir = File(System.getProperty("java.io.tmpdir"), "nuvio-torrserver").apply { mkdirs() }
            val extracted = File(tempDir, name)
            if (extracted.exists()) return extracted

            val resourcePath = "/native/torrserver/$name"
            val input = P2pStreamingEngine::class.java.getResourceAsStream(resourcePath)
                ?: throw P2pStreamingException(
                    "TorrServer binary not found. Expected at $resourcePath or one of: ${candidates.joinToString { it.path }}"
                )

            input.use { source ->
                extracted.outputStream().use { target ->
                    source.copyTo(target)
                }
            }

            if (System.getProperty("os.name").lowercase().contains("win").not()) {
                extracted.setExecutable(true)
            }

            println("$TAG: Extracted TorrServer binary to ${extracted.absolutePath}")
            return extracted
        }

        suspend fun isRunning(): Boolean {
            return try {
                val response = httpClient.get("$baseUrl/echo")
                response.status.value in 200..399
            } catch (e: Exception) {
                false
            }
        }

        fun stop() {
            try {
                runBlocking {
                    httpClient.get("$baseUrl/shutdown")
                }
            } catch (_: Exception) {
            }

            process?.let { proc ->
                try {
                    Thread.sleep(3_000L)
                    if (isProcessAlive(proc)) {
                        proc.destroyForcibly()
                    }
                } catch (_: Exception) {
                    proc.destroyForcibly()
                }
            }
            process = null
            println("$TAG: TorrServer stopped")
        }

        private fun killOrphanedProcess() {
            try {
                runBlocking {
                    httpClient.get("$baseUrl/shutdown")
                }
                Thread.sleep(1_000L)
                println("$TAG: Shut down orphaned TorrServer instance")
            } catch (_: Exception) {
            }
        }

        private fun isProcessAlive(proc: Process?): Boolean {
            if (proc == null) return false
            return try {
                proc.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            } catch (_: Exception) {
                false
            }
        }

        companion object {
            const val PORT = 8091
            private const val STARTUP_TIMEOUT_MS = 15_000L
            private const val HEALTH_CHECK_INTERVAL_MS = 200L
        }
    }

    private data class TorrServerFile(
        val id: Int,
        val path: String,
        val length: Long,
    )

    private data class TorrServerStats(
        val downloadSpeed: Long,
        val uploadSpeed: Long,
        val peers: Int,
        val seeds: Int,
        val preloadedBytes: Long,
        val loadedSize: Long,
        val torrentSize: Long,
        val files: List<TorrServerFile>,
    )

    private class TorrServerApi(
        private val binary: TorrServerBinary,
    ) {
        private val httpClient = HttpClient(CIO) {
            engine {
                requestTimeout = 30_000
            }
            expectSuccess = false
        }

        private val json = Json { ignoreUnknownKeys = true }

        private val baseUrl: String get() = binary.baseUrl

        suspend fun addTorrent(magnetLink: String, title: String? = null): String? = withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("action", "add")
                put("link", magnetLink)
                put("save_to_db", false)
                if (title != null) put("title", title)
            }

            try {
                val response = httpClient.post("$baseUrl/torrents") {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
                if (response.status.value !in 200..399) {
                    println("$TAG: addTorrent failed: ${response.status}")
                    return@withContext null
                }
                val responseBody = response.bodyAsText()
                val json = json.parseToJsonElement(responseBody).jsonObject
                val hash = json["hash"]?.jsonPrimitive?.content ?: ""
                println("$TAG: Torrent added: $hash")
                hash.ifEmpty { null }
            } catch (e: Exception) {
                println("$TAG: addTorrent error: ${e.message}")
                null
            }
        }

        suspend fun getTorrentStats(hash: String): TorrServerStats? = withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("action", "get")
                put("hash", hash)
            }

            try {
                val response = httpClient.post("$baseUrl/torrents") {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
                if (response.status.value !in 200..399) return@withContext null
                val responseBody = response.bodyAsText()
                val jsonObject = json.parseToJsonElement(responseBody).jsonObject

                val files = mutableListOf<TorrServerFile>()
                val fileList = jsonObject["file_stats"]?.jsonArray ?: buildJsonArray { }
                for (i in 0 until fileList.size) {
                    val file = fileList[i].jsonObject
                    files.add(
                        TorrServerFile(
                            id = file["id"]?.jsonPrimitive?.int ?: (i + 1),
                            path = file["path"]?.jsonPrimitive?.content ?: "",
                            length = file["length"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        ),
                    )
                }

                TorrServerStats(
                    downloadSpeed = jsonObject["download_speed"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    uploadSpeed = jsonObject["upload_speed"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    peers = jsonObject["active_peers"]?.jsonPrimitive?.int ?: 0,
                    seeds = jsonObject["connected_seeders"]?.jsonPrimitive?.int ?: 0,
                    preloadedBytes = jsonObject["preloaded_bytes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    loadedSize = jsonObject["loaded_size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    torrentSize = jsonObject["torrent_size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    files = files,
                )
            } catch (e: Exception) {
                println("$TAG: getTorrentStats error: ${e.message}")
                null
            }
        }

        suspend fun dropTorrent(hash: String) = withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("action", "drop")
                put("hash", hash)
            }

            try {
                httpClient.post("$baseUrl/torrents") {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
                println("$TAG: Torrent dropped: $hash")
            } catch (e: Exception) {
                println("$TAG: dropTorrent error: ${e.message}")
            }
        }

        fun getStreamUrl(magnetLink: String, fileIdx: Int): String {
            val encodedLink = URLEncoder.encode(magnetLink, "UTF-8")
            return "$baseUrl/stream?link=$encodedLink&index=$fileIdx&play"
        }
    }
}
