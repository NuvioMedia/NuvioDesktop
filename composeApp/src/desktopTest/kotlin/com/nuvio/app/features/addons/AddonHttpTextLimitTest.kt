package com.nuvio.app.features.addons

import com.nuvio.app.core.network.API_RESPONSE_MAX_BYTES
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AddonHttpTextLimitTest {
    @Test
    fun text_responses_above_the_limit_are_truncated_instead_of_buffered_whole() {
        val oversizedBody = ByteArray(API_RESPONSE_MAX_BYTES + 4_096) { 'a'.code.toByte() }

        withServer(oversizedBody) { url ->
            val body = runBlocking { httpGetText(url) }

            assertTrue(body.endsWith("[truncated]"), "oversized addon responses must be cut off")
            assertTrue(
                body.length <= API_RESPONSE_MAX_BYTES + 32,
                "kept ${body.length} chars, expected at most ${API_RESPONSE_MAX_BYTES + 32}",
            )
        }
    }

    @Test
    fun responses_below_the_limit_are_returned_unchanged() {
        val manifest = """{"id":"demo.addon","name":"Demo"}"""

        withServer(manifest.toByteArray()) { url ->
            assertEquals(manifest, runBlocking { httpGetText(url) })
        }
    }

    private fun withServer(body: ByteArray, block: (url: String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { output -> output.write(body) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/manifest.json")
        } finally {
            server.stop(0)
        }
    }
}
