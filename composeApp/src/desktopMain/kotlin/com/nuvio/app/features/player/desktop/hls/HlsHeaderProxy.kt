package com.nuvio.app.features.player.desktop.hls

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Loopback reverse proxy that replays the player's HTTP headers on every
 * upstream request.
 *
 * FFmpeg's HLS demuxer opens segments, keys and init sections on its own and
 * does not inherit mpv's `http-header-fields`, so header-protected HLS stalls
 * after the playlist loads. The player is pointed at this proxy instead; the
 * proxy fetches upstream with the add-on headers, rewrites playlists so their
 * child URIs keep flowing through it, and relays media byte for byte.
 *
 * Security invariants:
 * - binds to loopback only, on an ephemeral port;
 * - every request must carry the per-session token and a URL HMAC, both
 *   verified in constant time, so it cannot be used as an open relay;
 * - only http(s) targets are fetched; loopback, link-local and cloud metadata
 *   addresses are refused;
 * - client request headers are never forwarded upstream;
 * - URLs and header values are never logged.
 */
internal class HlsHeaderProxy(
    private val headers: Map<String, String>,
    private val client: HttpClient = defaultClient(),
    private val targetPolicy: (URI) -> Boolean = ::isDefaultAllowedHlsTarget,
) : AutoCloseable {

    companion object {
        private const val TOKEN_BYTES = 32
        private const val PATH_PREFIX = "s"
        private const val MAX_TARGET_URL_LENGTH = 8 * 1024
        private const val MAX_PLAYLIST_BYTES = 8 * 1024 * 1024
        private const val MAX_CONCURRENT_REQUESTS = 16
        private const val MAX_REDIRECTS = 5
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val SYSTEM_PROPERTY = "nuvio.desktop.hlsHeaderProxy"
        private const val FORBIDDEN_RESPONSE_STATUS = 403

        private val HOP_BY_HOP_HEADERS = setOf(
            "host",
            "connection",
            "keep-alive",
            "content-length",
            "transfer-encoding",
            "proxy-authorization",
            "proxy-authenticate",
            "proxy-connection",
            "te",
            "trailer",
            "upgrade",
            "expect",
        )
        private val SENSITIVE_REDIRECT_HEADERS = setOf(
            "authorization",
            "cookie",
            "proxy-authorization",
        )
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        // Content-Length is set by sendResponseHeaders, never copied twice.
        private val RELAYED_RESPONSE_HEADERS = listOf(
            "content-type",
            "content-range",
            "accept-ranges",
        )
        private val RANGE_PATTERN = Regex("^bytes=\\d*-(\\d*)$")

        /** Multi-label public suffixes needed to compare redirect domains safely. */
        private val MULTI_LABEL_PUBLIC_SUFFIXES = setOf(
            "co.uk", "org.uk", "ac.uk", "co.jp", "com.au", "net.au", "org.au",
            "co.nz", "com.br", "com.mx", "co.za", "co.in", "com.ar",
        )

        fun isEnabled(): Boolean =
            !System.getProperty(SYSTEM_PROPERTY).orEmpty().trim().equals("off", ignoreCase = true)

        private fun defaultClient(): HttpClient =
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
    }

    private val random = SecureRandom()
    private val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).toBase64Url()
    private val signingKey = ByteArray(TOKEN_BYTES).also(random::nextBytes)
    private val permits = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val executor = Executors.newFixedThreadPool(4, daemonThreadFactory())
    private var server: HttpServer? = null

    val port: Int
        get() = server?.address?.port ?: -1

    fun start(): HlsHeaderProxy {
        check(server == null) { "HLS header proxy already started" }
        val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        created.executor = executor
        created.createContext("/") { exchange -> handle(exchange) }
        created.start()
        server = created
        return this
    }

    /** Local URL that replays this session's headers when the player requests it. */
    fun localUrlFor(targetUrl: String): String {
        val active = checkNotNull(server) { "HLS header proxy is not started" }
        val encoded = targetUrl.toByteArray(Charsets.UTF_8).toBase64Url()
        return "http://127.0.0.1:${active.address.port}/$PATH_PREFIX/$token/${sign(encoded)}/$encoded"
    }

    override fun close() {
        server?.let { active -> runCatching { active.stop(0) } }
        server = null
        executor.shutdownNow()
        runCatching { executor.awaitTermination(1, TimeUnit.SECONDS) }
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                exchange.respond(405, "Method Not Allowed")
                return
            }
            if (!permits.tryAcquire()) {
                exchange.respond(503, "Busy")
                return
            }
            try {
                val target = resolveTarget(exchange.requestURI)
                if (target == null) {
                    exchange.respond(FORBIDDEN_RESPONSE_STATUS, "Forbidden")
                    return
                }
                val range = exchange.requestHeaders.getFirst("Range")?.takeIf(RANGE_PATTERN::matches)
                relay(exchange, target, range)
            } finally {
                permits.release()
            }
        } catch (_: Exception) {
            runCatching { exchange.respond(502, "Bad Gateway") }
        } finally {
            exchange.close()
        }
    }

    private fun resolveTarget(requestUri: URI): URI? {
        val segments = (requestUri.rawPath ?: return null).trim('/').split('/')
        if (segments.size != 4 || segments[0] != PATH_PREFIX) return null
        val (_, requestToken, signature, encoded) = segments
        if (encoded.length > MAX_TARGET_URL_LENGTH * 2) return null
        if (!constantTimeEquals(requestToken, token)) return null
        if (!constantTimeEquals(signature, sign(encoded))) return null

        val target = runCatching { String(encoded.fromBase64Url(), Charsets.UTF_8) }.getOrNull() ?: return null
        if (target.length > MAX_TARGET_URL_LENGTH) return null
        return runCatching { URI(target) }.getOrNull()?.takeIf(::isFetchableTarget)
    }

    private fun isFetchableTarget(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.host.isNullOrBlank()) return false
        return targetPolicy(uri)
    }

    private fun relay(exchange: HttpExchange, target: URI, range: String?) {
        var currentUrl = target
        var currentHeaders = headers
        var redirects = 0
        while (true) {
            val response = client.send(
                buildRequest(currentUrl, currentHeaders, range, exchange.requestMethod == "HEAD"),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            val location = response.headers().firstValue("location").orElse(null)
            if (location != null && response.statusCode() in REDIRECT_STATUSES && redirects < MAX_REDIRECTS) {
                response.body().close()
                val next = currentUrl.resolve(location)
                if (!isFetchableTarget(next)) {
                    exchange.respond(502, "Bad Gateway")
                    return
                }
                if (registrableDomain(next.host) != registrableDomain(currentUrl.host)) {
                    currentHeaders = currentHeaders.filterKeys {
                        it.lowercase() !in SENSITIVE_REDIRECT_HEADERS
                    }
                }
                currentUrl = next
                redirects += 1
                continue
            }
            sendResponse(exchange, response, currentUrl)
            return
        }
    }

    private fun buildRequest(
        url: URI,
        requestHeaders: Map<String, String>,
        range: String?,
        headOnly: Boolean,
    ): HttpRequest {
        val builder = HttpRequest.newBuilder(url)
            .timeout(Duration.ofSeconds(30))
            .header("Accept-Encoding", "identity")
        requestHeaders.forEach { (name, value) ->
            if (name.lowercase() !in HOP_BY_HOP_HEADERS) {
                builder.header(name, value)
            }
        }
        range?.let { builder.header("Range", it) }
        return if (headOnly) {
            builder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
        } else {
            builder.GET().build()
        }
    }

    private fun sendResponse(
        exchange: HttpExchange,
        response: HttpResponse<InputStream>,
        finalUrl: URI,
    ) {
        val body = response.body()
        try {
            val status = response.statusCode()
            val contentType = response.headers().firstValue("content-type").orElse("")
            if (status in 200..299 && isPlaylistCandidate(contentType)) {
                val buffered = body.readUpTo(MAX_PLAYLIST_BYTES)
                if (buffered.looksLikePlaylist()) {
                    val rewritten = HlsPlaylistRewriter.rewrite(
                        playlist = String(buffered, Charsets.UTF_8),
                        baseUrl = finalUrl.toString(),
                        mapUri = ::localUrlFor,
                    ).toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
                    exchange.responseHeaders.set("Cache-Control", "no-store")
                    exchange.sendResponseHeaders(status, rewritten.size.toLong())
                    if (exchange.requestMethod != "HEAD") {
                        exchange.responseBody.use { it.write(rewritten) }
                    }
                    return
                }
                writeRelayed(exchange, response, buffered)
                return
            }
            writeRelayed(exchange, response, buffered = null)
        } finally {
            runCatching { body.close() }
        }
    }

    private fun writeRelayed(
        exchange: HttpExchange,
        response: HttpResponse<InputStream>,
        buffered: ByteArray?,
    ) {
        val status = response.statusCode()
        RELAYED_RESPONSE_HEADERS.forEach { name ->
            response.headers().firstValue(name).ifPresent { value ->
                exchange.responseHeaders.set(name, value)
            }
        }
        val hasBody = status != 204 && status != 304 && exchange.requestMethod != "HEAD"
        val contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L)
        val declaredLength = when {
            !hasBody -> -1L
            contentLength >= 0 -> contentLength
            else -> 0L
        }
        exchange.sendResponseHeaders(status, declaredLength)
        if (!hasBody) return
        exchange.responseBody.use { out ->
            buffered?.let { out.write(it) }
            response.body().copyTo(out, STREAM_BUFFER_BYTES)
        }
    }

    private fun isPlaylistCandidate(contentType: String): Boolean {
        val normalized = contentType.lowercase()
        return normalized.isBlank() ||
            "mpegurl" in normalized ||
            normalized.startsWith("text/") ||
            "octet-stream" in normalized
    }

    private fun ByteArray.looksLikePlaylist(): Boolean {
        val text = toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return text.startsWith("#EXTM3U")
    }

    private fun InputStream.readUpTo(limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, STREAM_BUFFER_BYTES))
        val chunk = ByteArray(16 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val read = read(chunk, 0, minOf(chunk.size, remaining))
            if (read <= 0) break
            output.write(chunk, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }

    private fun sign(encoded: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey, "HmacSHA256"))
        return mac.doFinal(encoded.toByteArray(Charsets.UTF_8)).toBase64Url()
    }

    private fun registrableDomain(host: String?): String {
        val normalized = host.orEmpty().lowercase().trimEnd('.')
        val labels = normalized.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return normalized
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in MULTI_LABEL_PUBLIC_SUFFIXES) {
            labels.takeLast(3).joinToString(".")
        } else {
            lastTwo
        }
    }

    private fun HttpExchange.respond(status: Int, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun constantTimeEquals(candidate: String, expected: String): Boolean =
        MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )

    private fun ByteArray.toBase64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private fun String.fromBase64Url(): ByteArray = Base64.getUrlDecoder().decode(this)

    private fun daemonThreadFactory(): ThreadFactory {
        val index = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, "nuvio-hls-proxy-${index.incrementAndGet()}").apply { isDaemon = true }
        }
    }

}

private val BLOCKED_HLS_TARGET_HOSTS = setOf(
    "169.254.169.254",
    "100.100.100.200",
    "fd00:ec2::254",
    "metadata.google.internal",
    "metadata",
    // Loopback by name, not only by literal IP.
    "localhost",
    "localhost.localdomain",
    "ip6-localhost",
    "ip6-loopback",
)

/**
 * Default target policy: http(s) only, and never loopback, link-local or cloud
 * metadata endpoints. Private LAN ranges stay reachable for self-hosted media
 * servers. Tests inject their own policy because their origin listens on
 * loopback.
 */
internal fun isDefaultAllowedHlsTarget(uri: URI): Boolean {
    val host = uri.host?.trim()?.lowercase()
        ?.removeSurrounding("[", "]")
        ?.removeSuffix(".")
        ?: return false
    if (host.isBlank() || host in BLOCKED_HLS_TARGET_HOSTS) return false
    val literal = parseHlsIpLiteral(host) ?: return true
    return !(
        literal.isAnyLocalAddress ||
            literal.isLoopbackAddress ||
            literal.isLinkLocalAddress ||
            literal.isMulticastAddress
    )
}

private fun parseHlsIpLiteral(host: String): InetAddress? {
    val looksLikeIpv4 = host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }
    if (!looksLikeIpv4 && !host.contains(':')) return null
    return runCatching { InetAddress.getByName(host) }.getOrNull()
}
