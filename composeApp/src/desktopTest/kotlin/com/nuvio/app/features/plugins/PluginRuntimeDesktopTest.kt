package com.nuvio.app.features.plugins

import com.sun.net.httpserver.HttpServer
import com.nuvio.app.features.plugins.runtime.PluginRuntime
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class PluginRuntimeDesktopTest {
    @Test
    fun `desktop fetch preserves binary request and response bytes`() = runBlocking {
        val postedBytes = AtomicReference<ByteArray>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/binary") { exchange ->
            val bytes = exchange.requestBody.use { it.readBytes() }
            postedBytes.set(bytes)
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/text") { exchange ->
            val bytes = exchange.requestBody.use { it.readBytes() }
            assertContentEquals("hello".encodeToByteArray(), bytes)
            val reply = """{"value":"ok"}""".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val results = PluginRuntime.executePlugin(
                code = """
                    module.exports.getStreams = async function() {
                        var source = new Uint8Array([9, 0, 1, 127, 128, 255, 9]);
                        var binary = await fetch('http://127.0.0.1:${server.address.port}/binary', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/octet-stream' },
                            body: source.subarray(1, 6)
                        });
                        var bytes = new Uint8Array(await binary.arrayBuffer());
                        var text = await fetch('http://127.0.0.1:${server.address.port}/text', {
                            method: 'POST',
                            body: 'hello'
                        });
                        var data = await text.json();
                        return [{
                            title: Array.prototype.join.call(bytes, ',') + '|' + data.value + '|' + typeof binary.arrayBuffer,
                            url: 'https://example.test/binary.mp4'
                        }];
                    };
                """.trimIndent(),
                tmdbId = "603",
                mediaType = "movie",
                season = null,
                episode = null,
                scraperId = "desktop-binary-fetch-test",
            )
            assertContentEquals(byteArrayOf(0, 1, 127, -128, -1), postedBytes.get())
            assertEquals("0,1,127,128,255|ok|function", results.single().title)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `desktop runtime executes scraper code`() = runBlocking {
        val results = PluginRuntime.executePlugin(
            code = """
                module.exports.getStreams = async function(tmdbId, mediaType) {
                    return [{
                        title: "Desktop stream " + tmdbId + " " + mediaType,
                        url: "https://example.test/movie.mp4",
                        quality: "1080p",
                        provider: "Desktop Test"
                    }];
                };
            """.trimIndent(),
            tmdbId = "603",
            mediaType = "movie",
            season = null,
            episode = null,
            scraperId = "desktop-runtime-test",
        )

        assertEquals(1, results.size)
        assertEquals("Desktop stream 603 movie", results.single().title)
        assertEquals("https://example.test/movie.mp4", results.single().url)
        assertEquals("1080p", results.single().quality)
        assertEquals("Desktop Test", results.single().provider)
    }

    @Test
    fun `desktop runtime handles concurrent scraper executions`() = runBlocking {
        val results = coroutineScope {
            (0 until 32).map { index ->
                async(Dispatchers.Default) {
                    PluginRuntime.executePlugin(
                        code = """
                            module.exports.getStreams = async function(tmdbId, mediaType) {
                                await Promise.resolve();
                                return [{
                                    title: "Concurrent stream " + tmdbId + " " + mediaType,
                                    url: "https://example.test/" + tmdbId + ".mp4",
                                    provider: "Desktop Stress Test"
                                }];
                            };
                        """.trimIndent(),
                        tmdbId = index.toString(),
                        mediaType = "movie",
                        season = null,
                        episode = null,
                        scraperId = "desktop-runtime-stress-$index",
                    )
                }
            }.awaitAll()
        }

        assertEquals(32, results.size)
        results.forEachIndexed { index, streams ->
            assertEquals(1, streams.size)
            assertEquals("https://example.test/$index.mp4", streams.single().url)
        }
    }
}
