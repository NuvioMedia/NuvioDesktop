package com.nuvio.app.features.player.desktop.hls

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HlsHeaderProxyTest {
    private val referer = "https://app.example/watch"
    private val origins = mutableListOf<HttpServer>()
    private val observedReferers = CopyOnWriteArrayList<Pair<String, String?>>()
    private val observedHeaders = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()

    private val masterPlaylist = listOf(
        "#EXTM3U",
        "#EXT-X-VERSION:3",
        "#EXT-X-STREAM-INF:BANDWIDTH=800000",
        "variant/index.m3u8",
    ).joinToString("\n")

    private val mediaPlaylist = listOf(
        "#EXTM3U",
        "#EXT-X-VERSION:7",
        "#EXT-X-TARGETDURATION:4",
        "#EXT-X-MAP:URI=\"init.mp4\"",
        "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"",
        "#EXTINF:4,",
        "seg1.ts",
    ).joinToString("\n")

    @AfterTest
    fun stopOrigins() {
        origins.forEach { server -> server.stop(0) }
        origins.clear()
    }

    @Test
    fun replays_headers_on_playlists_segments_keys_and_init_sections() {
        val origin = startOrigin()
        val proxy = HlsHeaderProxy(
            headers = mapOf("Referer" to referer, "Origin" to "https://app.example"),
            targetPolicy = { true },
        )
        try {
            proxy.start()
            val client = testClient()

            val master = get(client, proxy.localUrlFor("$origin/master.m3u8"))
            assertEquals(200, master.statusCode())
            val variantUrl = master.body().lineSequence()
                .first { it.isNotBlank() && !it.startsWith("#") }

            val media = get(client, variantUrl)
            assertEquals(200, media.statusCode())
            val lines = media.body().lineSequence().toList()
            val segmentUrl = lines.first { it.isNotBlank() && !it.startsWith("#") }
            val initUrl = lines.first { it.startsWith("#EXT-X-MAP") }
                .substringAfter("URI=\"").substringBefore('"')
            val keyUrl = lines.first { it.startsWith("#EXT-X-KEY") }
                .substringAfter("URI=\"").substringBefore('"')

            assertEquals(200, get(client, segmentUrl).statusCode())
            assertEquals(200, get(client, initUrl).statusCode())
            assertEquals(200, get(client, keyUrl).statusCode())
        } finally {
            proxy.close()
        }

        assertEquals(
            listOf(referer),
            observedReferers.filter { it.first == "/variant/seg1.ts" }.map { it.second },
        )
        assertTrue(observedReferers.any { it.first == "/variant/key.bin" && it.second == referer })
        assertTrue(observedReferers.any { it.first == "/variant/init.mp4" && it.second == referer })
        assertTrue(observedReferers.any { it.first == "/master.m3u8" && it.second == referer })
    }

    @Test
    fun forwards_range_requests_and_partial_responses() {
        val origin = startOrigin()
        val proxy = HlsHeaderProxy(mapOf("Referer" to referer), targetPolicy = { true })
        try {
            proxy.start()
            val response = get(
                client = testClient(),
                url = proxy.localUrlFor("$origin/public.ts"),
                headers = mapOf("Range" to "bytes=0-3"),
            )

            assertEquals(206, response.statusCode())
            assertEquals("0123", response.body())
            assertEquals("bytes 0-3/10", response.headers().firstValue("content-range").orElse(null))
        } finally {
            proxy.close()
        }
    }

    @Test
    fun rejects_missing_and_tampered_signatures() {
        val origin = startOrigin()
        val proxy = HlsHeaderProxy(mapOf("Referer" to referer))
        try {
            proxy.start()
            val client = testClient()
            val uri = URI.create(proxy.localUrlFor("$origin/master.m3u8"))
            val segments = uri.rawPath.trim('/').split('/')
            val base = "http://${uri.host}:${uri.port}"

            assertEquals(403, get(client, "$base/s/${segments[1]}/AAAA/${segments[3]}").statusCode())
            assertEquals(403, get(client, "$base/").statusCode())
            assertEquals(403, get(client, "$base/s/token/signature/encoded").statusCode())
        } finally {
            proxy.close()
        }

        assertTrue(observedReferers.isEmpty(), "rejected requests must never reach the origin")
    }

    @Test
    fun refuses_loopback_metadata_and_non_http_targets() {
        val proxy = HlsHeaderProxy(mapOf("Referer" to referer))
        try {
            proxy.start()
            val client = testClient()

            assertEquals(403, get(client, proxy.localUrlFor("http://127.0.0.1:1/")).statusCode())
            assertEquals(403, get(client, proxy.localUrlFor("http://169.254.169.254/latest/meta-data/")).statusCode())
            assertEquals(403, get(client, proxy.localUrlFor("http://[::1]/")).statusCode())
            assertEquals(403, get(client, proxy.localUrlFor("file:///etc/passwd")).statusCode())
        } finally {
            proxy.close()
        }
    }

    @Test
    fun does_not_forward_client_request_headers() {
        val origin = startOrigin()
        val proxy = HlsHeaderProxy(mapOf("Referer" to referer), targetPolicy = { true })
        try {
            proxy.start()
            val response = get(
                client = testClient(),
                url = proxy.localUrlFor("$origin/public.txt"),
                headers = mapOf("X-Leak" to "1"),
            )

            assertEquals(200, response.statusCode())
        } finally {
            proxy.close()
        }

        val forwarded = observedHeaders.first { it.first == "/public.txt" }.second
        assertTrue("x-leak" !in forwarded, "client headers must not be forwarded upstream")
    }

    private fun startOrigin(): String {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val headers = exchange.requestHeaders.entries
                .associate { it.key.lowercase() to it.value.joinToString(",") }
            observedReferers += path to headers["referer"]
            observedHeaders += path to headers

            if (path !in setOf("/public.ts", "/public.txt") && headers["referer"] != referer) {
                exchange.respondText(403, "forbidden")
                return@createContext
            }

            when (path) {
                "/master.m3u8" -> exchange.respondText(200, masterPlaylist, "application/vnd.apple.mpegurl")
                "/variant/index.m3u8" -> exchange.respondText(200, mediaPlaylist, "application/vnd.apple.mpegurl")
                "/variant/key.bin" -> exchange.respondBytes(200, byteArrayOf(1, 2, 3))
                "/variant/init.mp4" -> exchange.respondBytes(200, byteArrayOf(0, 0, 0, 0))
                "/variant/seg1.ts" -> exchange.respondBytes(200, byteArrayOf(9, 8, 7), "video/mp2t")
                "/public.txt" -> exchange.respondText(200, "public")
                "/public.ts" -> {
                    val bytes = "0123456789".toByteArray()
                    if (headers["range"] == "bytes=0-3") {
                        exchange.responseHeaders.set("Content-Type", "video/mp2t")
                        exchange.responseHeaders.set("Content-Range", "bytes 0-3/10")
                        exchange.sendResponseHeaders(206, 4)
                        exchange.responseBody.use { it.write(bytes, 0, 4) }
                    } else {
                        exchange.respondBytes(200, bytes, "video/mp2t")
                    }
                }
                else -> exchange.respondText(404, "not found")
            }
        }
        server.start()
        origins += server
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun testClient(): HttpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    private fun get(
        client: HttpClient,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url)).GET()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun HttpExchange.respondText(status: Int, body: String, contentType: String = "text/plain") {
        val bytes = body.toByteArray()
        responseHeaders.set("Content-Type", contentType)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.respondBytes(status: Int, body: ByteArray, contentType: String = "application/octet-stream") {
        responseHeaders.set("Content-Type", contentType)
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }
}
