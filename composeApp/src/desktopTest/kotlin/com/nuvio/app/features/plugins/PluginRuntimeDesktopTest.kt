package com.nuvio.app.features.plugins

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginRuntimeDesktopTest {
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
    fun `desktop runtime provides working timers`() = runBlocking {
        val results = PluginRuntime.executePlugin(
            code = """
                module.exports.getStreams = async function(tmdbId, mediaType) {
                    // setTimeout resolves and its callback runs
                    var fired = await new Promise(function(resolve) {
                        setTimeout(function(value) { resolve(value); }, 50, "fired");
                    });

                    // clearTimeout prevents the callback from running
                    var cancelled = "not-cancelled";
                    var id = setTimeout(function() { cancelled = "leaked"; }, 50);
                    clearTimeout(id);
                    await new Promise(function(resolve) { setTimeout(resolve, 120); });

                    return [{
                        title: fired + " " + cancelled,
                        url: "https://example.test/timers.mp4",
                        provider: "Timer Test"
                    }];
                };
            """.trimIndent(),
            tmdbId = "603",
            mediaType = "movie",
            season = null,
            episode = null,
            scraperId = "desktop-timer-test",
        )

        assertEquals(1, results.size)
        assertEquals("fired not-cancelled", results.single().title)
    }
}
