package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackHeadersTest {
    @Test
    fun keeps_referer_and_origin() {
        val sanitized = sanitizePlaybackHeaders(
            mapOf("Referer" to "https://app.example/watch", "Origin" to "https://app.example"),
        )

        assertEquals("https://app.example/watch", sanitized["Referer"])
        assertEquals("https://app.example", sanitized["Origin"])
    }

    @Test
    fun rejects_values_that_could_inject_a_request_line() {
        val sanitized = sanitizePlaybackHeaders(
            mapOf(
                "Referer" to "https://app.example\r\nX-Injected: 1",
                "Origin" to "https://app.example\nX-Also-Injected: 1",
                "X-Ok" to "value",
            ),
        )

        assertNull(sanitized["Referer"])
        assertNull(sanitized["Origin"])
        assertEquals("value", sanitized["X-Ok"])
    }

    @Test
    fun rejects_invalid_header_names() {
        val sanitized = sanitizePlaybackHeaders(
            mapOf(
                "Bad Name" to "value",
                "X-Ok" to "value",
                "" to "value",
                "X-Bad:\r\n" to "value",
            ),
        )

        assertEquals(setOf("X-Ok"), sanitized.keys)
    }

    @Test
    fun drops_hop_by_hop_and_range_headers() {
        val sanitized = sanitizePlaybackHeaders(
            mapOf(
                "Host" to "evil.example",
                "Connection" to "keep-alive",
                "Transfer-Encoding" to "chunked",
                "Range" to "bytes=0-1",
                "Referer" to "https://app.example",
            ),
        )

        assertEquals(setOf("Referer"), sanitized.keys)
    }

    @Test
    fun drops_headers_named_by_connection() {
        val sanitized = sanitizePlaybackHeaders(
            mapOf(
                "Connection" to "X-Secret, X-Other",
                "X-Secret" to "1",
                "X-Other" to "2",
                "X-Keep" to "3",
            ),
        )

        assertEquals(setOf("X-Keep"), sanitized.keys)
    }

    @Test
    fun bounds_header_count_and_value_size() {
        val tooMany = (0 until 40).associate { "X-$it" to "value" }
        assertEquals(32, sanitizePlaybackHeaders(tooMany).size)

        val tooBig = mapOf("X-Big" to "a".repeat(8 * 1024 + 1), "X-Ok" to "value")
        assertEquals(setOf("X-Ok"), sanitizePlaybackHeaders(tooBig).keys)
    }

    @Test
    fun drops_blank_values_and_handles_missing_input() {
        assertEquals(emptyMap(), sanitizePlaybackHeaders(mapOf("Referer" to "   ")))
        assertEquals(emptyMap(), sanitizePlaybackHeaders(null))
    }
}
