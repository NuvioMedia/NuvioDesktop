package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitlePlaybackFilenameTest {
    @Test fun decodesOnlyTheMediaBasename() {
        assertEquals("The Middle S04E17 NF-playWEB.mkv", subtitleFilenameFromPlaybackUrl("https://cdn.example/The%20Middle%20S04E17%20NF-playWEB.mkv?token=private#fragment"))
        assertEquals("A+B.mkv", subtitleFilenameFromPlaybackUrl("https://cdn.example/A+B.mkv"))
        assertEquals("日本語.mkv", subtitleFilenameFromPlaybackUrl("https://cdn.example/%E6%97%A5%E6%9C%AC%E8%AA%9E.mkv"))
    }
    @Test fun neverTreatsOpaqueOrSensitiveUrlPartsAsFilename() {
        for (url in listOf(null, "https://cdn.example/", "https://cdn.example/opaque.jwt.token?filename=movie.mkv", "https://cdn.example/stream", "https://cdn.example/x%2Fy.mkv", "https://cdn.example/x%00.mkv", "https://cdn.example/%ZZ.mkv", "https://cdn.example/%FF.mkv", "https://cdn.example/path/#movie.mkv", "file:///private/movie.mkv")) {
            assertNull(subtitleFilenameFromPlaybackUrl(url), url)
        }
    }
}
