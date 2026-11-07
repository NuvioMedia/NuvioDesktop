package com.nuvio.app.features.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImdbIdQueryTest {
    @Test
    fun `bare imdb ids are normalized`() {
        assertEquals("tt30460310", parseImdbIdQuery("tt30460310")?.imdbId)
        assertEquals("tt30460310", parseImdbIdQuery("  TT30460310\n")?.imdbId)
        assertEquals("tt30460310", parseImdbIdQuery("tt30460310/")?.imdbId)
        assertEquals("tt0111161", parseImdbIdQuery("tt0111161")?.imdbId)
    }

    @Test
    fun `imdb urls are reduced to the title id`() {
        assertEquals(
            "tt30460310",
            parseImdbIdQuery("https://www.imdb.com/title/tt30460310/")?.imdbId,
        )
        assertEquals(
            "tt30460310",
            parseImdbIdQuery("https://m.imdb.com/title/tt30460310?ref_=nv_sr_1")?.imdbId,
        )
        assertEquals(
            "tt30460310",
            parseImdbIdQuery("http://imdb.com/tt30460310")?.imdbId,
        )
        assertEquals(
            "tt30460310",
            parseImdbIdQuery("imdb.com/title/tt30460310")?.imdbId,
        )
        assertEquals(
            "tt30460310",
            parseImdbIdQuery("www.imdb.com/title/tt30460310/")?.imdbId,
        )
    }

    @Test
    fun `ordinary titles and malformed ids fall back to text search`() {
        for (value in listOf("", "tt123", "30460310", "tt", "attack tt42", "Dune", "Star Wars")) {
            assertNull(parseImdbIdQuery(value), value)
        }
    }

    @Test
    fun `urls without an imdb id are not treated as imdb queries`() {
        for (value in listOf(
            "https://www.imdb.com/list/ls123456789/",
            "https://www.imdb.com/find?q=dune",
            "https://example.com/title/tt123",
        )) {
            assertNull(parseImdbIdQuery(value), value)
        }
    }

    @Test
    fun `detection matches parsing before any network request`() {
        assertTrue(isImdbIdQuery("tt30460310"))
        assertTrue(isImdbIdQuery("https://www.imdb.com/title/tt30460310/"))
        assertFalse(isImdbIdQuery("tt123"))
        assertFalse(isImdbIdQuery("Spider Noir"))
    }
}
