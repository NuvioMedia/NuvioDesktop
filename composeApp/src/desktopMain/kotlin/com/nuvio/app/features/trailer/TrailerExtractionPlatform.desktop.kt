package com.nuvio.app.features.trailer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val PROBE_TIMEOUT_SECONDS = 5L
private const val PROBE_RACE_TIMEOUT_MS = 8_000L

internal object TrailerExtractionPlatform {
    val diagnosticsEnabled: Boolean = System.getenv("NUVIO_TRAILER_DEBUG")
        ?.trim()
        ?.lowercase()
        .let { it == "1" || it == "true" || it == "yes" || it == "on" }

    val defaultHeaders: Map<String, String> = mapOf(
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun supportsSeparateVideo(candidate: StreamCandidate): Boolean = candidate.ext == "mp4"

    fun supportsSeparateAudio(candidate: StreamCandidate): Boolean = candidate.ext == "m4a"

    fun diagnostic(message: String) {
        if (diagnosticsEnabled) {
            println("[TrailerDebug] $message")
        }
    }

    fun describeUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull()
        return "host=${parsed?.host ?: "unknown"} itag=${parsed?.queryParameter("itag") ?: "unknown"}"
    }

    suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMillis: Long,
    ): TrailerRequestResponse = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .headers(buildHeaders(headers))

        when (method.uppercase()) {
            "POST" -> requestBuilder.post((body ?: "").toRequestBody())
            "PUT" -> requestBuilder.put((body ?: "").toRequestBody())
            "DELETE" -> requestBuilder.delete()
            else -> requestBuilder.get()
        }

        httpClient.newBuilder()
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
            .newCall(requestBuilder.build())
            .execute().use { response ->
                TrailerRequestResponse(
                    ok = response.isSuccessful,
                    status = response.code,
                    statusText = response.message,
                    url = response.request.url.toString(),
                    body = response.body?.string().orEmpty(),
                )
            }
    }

    suspend fun buildPlaybackSource(
        manifestCandidates: List<ManifestCandidate>,
        progressiveCandidates: List<StreamCandidate>,
        videoCandidates: List<StreamCandidate>,
        audioCandidates: List<StreamCandidate>,
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        // A candidate can look fine and still be unplayable (PO-token gated URLs
        // answer the first range and 403 everything after), so every candidate is
        // probed and the first one that survives wins.
        var videoOnlyFallback: String? = null
        // A PO token gated client answers the first range and 403s the rest for
        // every one of its adaptive formats, so one rejection condemns them all.
        val gatedClients = mutableSetOf<String>()

        suspend fun fromManifests(): TrailerPlaybackSource? {
            for (candidate in manifestCandidates.take(MAX_CANDIDATE_ATTEMPTS)) {
                val url = resolveReachableUrlOrNull(candidate.manifestUrl) ?: continue
                diagnostic("selected mode=hls video=[${candidate.diagnosticSummary()}]")
                return TrailerPlaybackSource(videoUrl = url, audioUrl = null)
            }
            return null
        }

        suspend fun fromSeparateStreams(): TrailerPlaybackSource? {
            for (video in videoCandidates.take(MAX_CANDIDATE_ATTEMPTS)) {
                if (video.client in gatedClients) continue
                val videoUrl = resolveReachableUrlOrNull(video.url)
                if (videoUrl == null) {
                    gatedClients += video.client
                    diagnostic("blocked stage=video_probe candidate=${video.diagnosticSummary()}")
                    continue
                }
                for (audio in audioCandidates.take(MAX_CANDIDATE_ATTEMPTS)) {
                    val audioUrl = resolveReachableUrlOrNull(audio.url)
                    if (audioUrl == null) {
                        diagnostic("blocked stage=audio_probe candidate=${audio.diagnosticSummary()}")
                        continue
                    }
                    diagnostic(
                        "selected mode=adaptive_separate video=[${video.diagnosticSummary()}] " +
                            "audio=[${audio.diagnosticSummary()}]",
                    )
                    return TrailerPlaybackSource(videoUrl = videoUrl, audioUrl = audioUrl)
                }
                // Video plays but no audio track survived: remember it and let the
                // other strategies try to produce a source that still has sound.
                if (videoOnlyFallback == null) {
                    videoOnlyFallback = videoUrl
                }
                break
            }
            return null
        }

        suspend fun fromProgressive(): TrailerPlaybackSource? {
            for (candidate in progressiveCandidates.take(MAX_CANDIDATE_ATTEMPTS)) {
                val url = resolveReachableUrlOrNull(candidate.url) ?: continue
                diagnostic("selected mode=combined_fallback video=[${candidate.diagnosticSummary()}]")
                return TrailerPlaybackSource(videoUrl = url, audioUrl = null)
            }
            return null
        }

        val manifestHeight = manifestCandidates.firstOrNull()?.height ?: -1
        val separateHeight = videoCandidates.firstOrNull()?.height ?: -1
        val strategies: List<suspend () -> TrailerPlaybackSource?> = if (manifestHeight >= separateHeight) {
            listOf({ fromManifests() }, { fromSeparateStreams() }, { fromProgressive() })
        } else {
            listOf({ fromSeparateStreams() }, { fromManifests() }, { fromProgressive() })
        }

        for (strategy in strategies) {
            val source = strategy()
            if (source != null) {
                diagnostic("source videoUrl=${source.videoUrl}")
                diagnostic("source audioUrl=${source.audioUrl ?: "none"}")
                return@withContext source
            }
        }

        videoOnlyFallback?.let { videoUrl ->
            diagnostic("selected mode=adaptive_video_only")
            return@withContext TrailerPlaybackSource(videoUrl = videoUrl, audioUrl = null)
        }

        diagnostic("blocked stage=source reason=no_reachable_video")
        null
    }

    private suspend fun resolveReachableUrlOrNull(url: String): String? {
        if (!url.contains("googlevideo.com")) {
            diagnostic("probe skipped ${describeUrl(url)} reason=non_googlevideo")
            return url
        }
        val parsedUrl = url.toHttpUrlOrNull()
        if (parsedUrl == null) {
            diagnostic("probe failed host=unknown reason=invalid_url")
            return null
        }
        val servers = parsedUrl.queryParameter("mn")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val host = parsedUrl.host
        val candidates = buildList {
            add(url)
            servers.forEachIndexed { index, server ->
                val alternateHost = host
                    .replaceFirst(Regex("^rr\\d+---"), "rr${index + 1}---")
                    .replaceFirst(Regex("sn-[a-z0-9]+-[a-z0-9]+"), server)
                if (alternateHost != host) {
                    add(parsedUrl.newBuilder().host(alternateHost).build().toString())
                }
            }
        }.distinct()

        if (candidates.size == 1) {
            val selected = candidates.first().takeIf(::isUrlReachable)
            diagnostic(
                "probe ${if (selected != null) "ok" else "failed"} ${describeUrl(url)} candidates=1",
            )
            return selected
        }

        val result = CompletableDeferred<String>()
        val probeScope = CoroutineScope(Dispatchers.IO)
        candidates.forEach { candidate ->
            probeScope.launch {
                if (isUrlReachable(candidate)) {
                    result.complete(candidate)
                }
            }
        }

        return try {
            val selected = withTimeoutOrNull(PROBE_RACE_TIMEOUT_MS) { result.await() }
            diagnostic(
                "probe ${if (selected != null) "ok" else "failed"} ${describeUrl(url)} candidates=${candidates.size}" +
                    selected?.let { " selectedHost=${it.toHttpUrlOrNull()?.host ?: "unknown"}" }.orEmpty(),
            )
            selected
        } finally {
            probeScope.cancel()
        }
    }

    private fun isUrlReachable(url: String): Boolean = runCatching {
        val parsedUrl = url.toHttpUrlOrNull()
        val sourceSize = parsedUrl?.queryParameter("clen")?.toLongOrNull()?.takeIf { it > 0L }
        val ranges = sourceSize?.let { size ->
            listOf(
                0L to 65_535L.coerceAtMost(size - 1L),
                (size - 65_536L).coerceAtLeast(0L) to size - 1L,
            ).distinct()
        } ?: listOf(0L to 0L)

        ranges.all { (rangeStart, rangeEnd) ->
            val request = Request.Builder()
                .url(url)
                .headers(buildHeaders(defaultHeaders))
                .header("Range", "bytes=$rangeStart-$rangeEnd")
                .get()
                .build()

            probeClient.newCall(request).execute().use { response ->
                val reachable = response.code == 206 ||
                    (sourceSize == null && rangeStart == 0L && response.code in 200..299)
                if (!reachable) {
                    diagnostic(
                        "probe range rejected ${describeUrl(url)} requested=$rangeStart-$rangeEnd status=${response.code}",
                    )
                }
                reachable
            }
        }
    }.getOrDefault(false)

    private fun buildHeaders(source: Map<String, String>): Headers {
        val headers = Headers.Builder()
        source.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                headers.add(name, value)
            }
        }
        if (source.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers.add("User-Agent", defaultHeaders.getValue("user-agent"))
        }
        return headers.build()
    }
}
