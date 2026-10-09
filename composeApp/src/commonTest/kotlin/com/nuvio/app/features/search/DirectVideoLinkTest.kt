package com.nuvio.app.features.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectVideoLinkTest {
    @Test
    fun `signed links are preserved and titles exclude query and fragment`() {
        val url = "https://cdn.example/My%20Video.mp4?signature=a%2Bb&expires=123#start"
        val link = parseDirectVideoLink("  $url\n")!!
        assertEquals(url, link.url)
        assertEquals("My Video.mp4", link.title)
    }

    @Test
    fun `download endpoint filename is decoded without changing signed url`() {
        val url = "https://cdn.example/dld/abc?token=test%2Bsignature&filename=My%20Video.mp4"
        val link = parseDirectVideoLink(url)!!
        assertEquals(url, link.url)
        assertEquals("My Video.mp4", link.title)
    }

    @Test
    fun `streams and extensionless endpoints are accepted`() {
        for (path in listOf("live.m3u8", "manifest.mpd", "stream?id=42")) {
            assertEquals("http://localhost:1234/$path", parseDirectVideoLink("http://localhost:1234/$path")?.url)
        }
        assertEquals("cdn.example", parseDirectVideoLink("HTTPS://cdn.example/")?.title)
    }

    @Test
    fun `invalid and unsupported links do not launch`() {
        for (value in listOf("https://", "https:///video.mp4", "https://example.com/a b.mp4", "https://example.com/a\nb", "https://example.com:bad/a", "file:///video.mp4", "javascript://alert", "https:example.com", "movie title")) {
            assertNull(parseDirectVideoLink(value), value)
        }
    }

    @Test
    fun `link input is distinguished from ordinary search before contacting addons`() {
        for (value in listOf("https://cdn.example/a.mp4", "https://", "https:broken", "rtsp://example/live", "file:///a.mp4")) {
            assertTrue(isVideoLinkQuery(value), value)
        }
        for (value in listOf("", "Dune", "Star Wars: A New Hope", "Mr. Robot", "http documentary")) {
            assertFalse(isVideoLinkQuery(value), value)
        }
    }
}
