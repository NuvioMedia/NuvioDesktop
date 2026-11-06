package com.nuvio.app.features.player.desktop.hls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HlsPlaylistRewriterTest {
    private fun proxied(url: String): String = "http://127.0.0.1:1234/s/token/signature/$url"

    private fun mediaPlaylist(): String = listOf(
        "#EXTM3U",
        "#EXT-X-VERSION:7",
        "#EXT-X-TARGETDURATION:4",
        "#EXT-X-MAP:URI=\"init.mp4\"",
        "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x1",
        "#EXTINF:4,",
        "seg1.ts",
        "#EXTINF:4,",
        "https://cdn.example/seg2.ts",
    ).joinToString("\n")

    @Test
    fun rewrites_segments_keys_and_init_sections() {
        val rewritten = HlsPlaylistRewriter.rewrite(
            playlist = mediaPlaylist(),
            baseUrl = "https://cdn.example/variant/index.m3u8",
            mapUri = ::proxied,
        )

        assertTrue(rewritten.contains("URI=\"${proxied("https://cdn.example/variant/init.mp4")}\""))
        assertTrue(rewritten.contains("URI=\"${proxied("https://cdn.example/variant/key.bin")}\""))
        assertTrue(rewritten.contains("IV=0x1"))
        assertTrue(rewritten.contains(proxied("https://cdn.example/variant/seg1.ts")))
        assertTrue(rewritten.contains(proxied("https://cdn.example/seg2.ts")))
    }

    @Test
    fun rewrites_variant_playlists_and_keeps_stream_inf() {
        val playlist = listOf(
            "#EXTM3U",
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720",
            "720p/index.m3u8",
            "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=90000,URI=\"720p/iframe.m3u8\"",
        ).joinToString("\n")

        val rewritten = HlsPlaylistRewriter.rewrite(
            playlist = playlist,
            baseUrl = "https://cdn.example/master.m3u8",
            mapUri = ::proxied,
        )

        assertTrue(rewritten.contains("#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720"))
        assertTrue(rewritten.contains(proxied("https://cdn.example/720p/index.m3u8")))
        assertTrue(rewritten.contains("URI=\"${proxied("https://cdn.example/720p/iframe.m3u8")}\""))
    }

    @Test
    fun leaves_uri_lookalikes_inside_quoted_values_untouched() {
        val playlist = "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",KEYFORMAT=\"identity,URI=evil\"\n"

        val rewritten = HlsPlaylistRewriter.rewrite(
            playlist = playlist,
            baseUrl = "https://cdn.example/index.m3u8",
            mapUri = ::proxied,
        )

        assertTrue(rewritten.contains("KEYFORMAT=\"identity,URI=evil\""))
        assertTrue(rewritten.contains("URI=\"${proxied("https://cdn.example/key.bin")}\""))
    }

    @Test
    fun preserves_line_endings_and_comments() {
        val playlist = "#EXTM3U\r\n#EXT-X-VERSION:3\r\nseg1.ts\r\n"

        val rewritten = HlsPlaylistRewriter.rewrite(
            playlist = playlist,
            baseUrl = "https://cdn.example/index.m3u8",
            mapUri = ::proxied,
        )

        assertEquals("#EXTM3U\r\n#EXT-X-VERSION:3\r\n${proxied("https://cdn.example/seg1.ts")}\r\n", rewritten)
    }

    @Test
    fun returns_empty_input_unchanged() {
        assertEquals("", HlsPlaylistRewriter.rewrite("", "https://cdn.example/index.m3u8", ::proxied))
    }
}
