package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkipIntervalLookupTest {
    @Test
    fun resolvesImdbEpisodeFromPlayerMetadata() {
        assertEquals(
            SkipIntervalLookup.Imdb("tt1234567", season = 2, episode = 4),
            resolveSkipIntervalLookup("tt1234567:2:4", season = 2, episode = 4),
        )
    }

    @Test
    fun resolvesMalEpisodeEmbeddedInVideoId() {
        assertEquals(
            SkipIntervalLookup.Mal("62322", episode = 4),
            resolveSkipIntervalLookup("mal:62322:4", season = 1, episode = null),
        )
    }

    @Test
    fun usesActiveEpisodeForSeasonQualifiedMalId() {
        assertEquals(
            SkipIntervalLookup.Mal("63375", episode = 5),
            resolveSkipIntervalLookup("mal:63375:1:5", season = 1, episode = 5),
        )
    }

    @Test
    fun resolvesKitsuEpisodeEmbeddedInVideoId() {
        assertEquals(
            SkipIntervalLookup.Kitsu("12345", episode = 8),
            resolveSkipIntervalLookup("kitsu:12345:8", season = null, episode = null),
        )
    }

    @Test
    fun rejectsUnsupportedOrIncompleteIds() {
        assertNull(resolveSkipIntervalLookup("tmdb:123", season = 1, episode = 2))
        assertNull(resolveSkipIntervalLookup("mal:62322", season = 1, episode = null))
    }
}
