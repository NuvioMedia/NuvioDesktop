package com.nuvio.app.features.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TmdbImageUrlsTest {

    @Test
    fun `w1280 tmdb image uses original size`() {
        assertEquals(
            "https://image.tmdb.org/t/p/original/backdrop.jpg?language=en",
            originalTmdbImageUrl("https://image.tmdb.org/t/p/w1280/backdrop.jpg?language=en"),
        )
    }

    @Test
    fun `other tmdb sizes remain unchanged`() {
        val posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg"

        assertEquals(posterUrl, originalTmdbImageUrl(posterUrl))
    }

    @Test
    fun `non tmdb urls remain unchanged`() {
        val imageUrl = "https://example.com/t/p/w1280/backdrop.jpg"

        assertEquals(imageUrl, originalTmdbImageUrl(imageUrl))
    }

    @Test
    fun `null image url remains null`() {
        assertNull(originalTmdbImageUrl(null))
    }
}
