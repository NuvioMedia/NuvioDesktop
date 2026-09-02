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
        .dns(com.nuvio.app.core.network.DesktopIPv4FirstDns())
        .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
        .connectTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .dns(com.nuvio.app.core.network.DesktopIPv4FirstDns())
        .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
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
        bestManifest: ManifestCandidate?,
        bestProgressive: StreamCandidate?,
        bestVideo: StreamCandidate?,
        bestAudio: StreamCandidate?,
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        val bestCombinedIsManifest = bestManifest != null &&
            (bestProgressive == null || bestManifest.height > bestProgressive.height)
        val preferManifestPlayback = bestManifest != null &&
            (bestVideo == null || bestManifest.height >= bestVideo.height)
        val combinedUrl = if (bestCombinedIsManifest) {
            bestManifest.manifestUrl
        } else {
            bestProgressive?.url
        }

        val separatedVideoUrl = if (preferManifestPlayback) {
            null
        } else {
            bestVideo?.url?.let { resolveReachableUrlOrNull(it) }
        }
        if (!preferManifestPlayback && bestVideo != null && separatedVideoUrl == null) {
            diagnostic("blocked stage=video_probe candidate=${bestVideo.diagnosticSummary()}")
        }
        val separatedAudioUrl = if (!separatedVideoUrl.isNullOrBlank()) {
            bestAudio?.url?.let { resolveReachableUrlOrNull(it) }
        } else {
            null
        }
        if (separatedVideoUrl != null && bestAudio != null && separatedAudioUrl == null) {
            diagnostic("blocked stage=audio_probe candidate=${bestAudio.diagnosticSummary()}")
        }
        val useSeparatedStreams = separatedVideoUrl != null && separatedAudioUrl != null
        val combinedCandidateUrl = if (!useSeparatedStreams) {
            combinedUrl?.let { resolveReachableUrlOrNull(it) }
        } else {
            null
        }
        val videoUrl = if (useSeparatedStreams) separatedVideoUrl else combinedCandidateUrl ?: separatedVideoUrl
        if (videoUrl == null) {
            diagnostic("blocked stage=source reason=no_reachable_video")
            return@withContext null
        }
        val audioUrl = separatedAudioUrl.takeIf { useSeparatedStreams }
        val mode = when {
            useSeparatedStreams -> "adaptive_separate"
            combinedCandidateUrl != null && bestCombinedIsManifest -> "hls"
            combinedCandidateUrl != null -> "combined_fallback"
            else -> "adaptive_video_only"
        }
        val videoSummary = when {
            useSeparatedStreams -> bestVideo.diagnosticSummary()
            combinedCandidateUrl != null && bestCombinedIsManifest -> bestManifest.diagnosticSummary()
            combinedCandidateUrl != null -> bestProgressive.diagnosticSummary()
            else -> bestVideo.diagnosticSummary()
        }
        diagnostic(
            "selected mode=$mode video=[$videoSummary] audio=[${bestAudio.takeIf { useSeparatedStreams }.diagnosticSummary()}]",
        )
        diagnostic("source videoUrl=$videoUrl")
        diagnostic("source audioUrl=${audioUrl ?: "none"}")
        TrailerPlaybackSource(
            videoUrl = videoUrl,
            audioUrl = audioUrl,
        )
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
            val selected = withTimeoutOrNull(4_000L) { result.await() }
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
