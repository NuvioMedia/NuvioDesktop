package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.tracking.WatchProgressSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContinueWatchingEnrichmentCacheTest {
    @Test
    fun `saving in progress rows preserves next up`() {
        val profileId = Int.MAX_VALUE
        val source = WatchProgressSource.TRAKT
        val generation = ContinueWatchingEnrichmentCache.generation.value
        val nextUp = listOf(
            CachedNextUpItem(
                contentId = "show",
                contentType = "series",
                name = "Show",
                videoId = "show:1:2",
                season = 1,
                episode = 2,
                lastWatched = 1_000L,
                sortTimestamp = 1_000L,
                seedSeason = 1,
                seedEpisode = 1,
            ),
        )

        try {
            assertTrue(ContinueWatchingEnrichmentCache.saveSnapshots(profileId, source, generation, nextUp = nextUp))
            assertTrue(ContinueWatchingEnrichmentCache.saveSnapshots(profileId, source, generation, inProgress = emptyList()))
            assertEquals(nextUp, ContinueWatchingEnrichmentCache.getNextUpSnapshot(profileId, source))
        } finally {
            ContinueWatchingEnrichmentCache.clearAll(profileId)
        }
    }


    @Test
    fun `storage keys are scoped by profile and effective source`() {
        val traktKey = ContinueWatchingEnrichmentCache.continueWatchingEnrichmentStorageKey(
            profileId = 2,
            source = WatchProgressSource.TRAKT,
        )
        val nuvioKey = ContinueWatchingEnrichmentCache.continueWatchingEnrichmentStorageKey(
            profileId = 2,
            source = WatchProgressSource.NUVIO_SYNC,
        )

        assertEquals("cw_enrichment_cache_trakt_2", traktKey)
        assertEquals("cw_enrichment_cache_nuvio_sync_2", nuvioKey)
        assertNotEquals(traktKey, nuvioKey)
        assertEquals("cw_enrichment_cache_2", ContinueWatchingEnrichmentCache.legacyStorageKey(profileId = 2))
    }

    @Test
    fun `stale resolver generation cannot write snapshots after invalidation`() {
        val profileId = 4
        val staleGeneration = ContinueWatchingEnrichmentCache.generation.value

        ContinueWatchingEnrichmentCache.invalidate(
            profileId = profileId,
            source = WatchProgressSource.TRAKT,
        )

        assertFalse(
            ContinueWatchingEnrichmentCache.saveSnapshots(
                profileId = profileId,
                source = WatchProgressSource.TRAKT,
                generation = staleGeneration,
                nextUp = emptyList(),
                inProgress = emptyList(),
            ),
        )
        assertTrue(
            ContinueWatchingEnrichmentCache.saveSnapshots(
                profileId = profileId,
                source = WatchProgressSource.TRAKT,
                generation = ContinueWatchingEnrichmentCache.generation.value,
                nextUp = emptyList(),
                inProgress = emptyList(),
            ),
        )

        ContinueWatchingEnrichmentCache.clearAll(profileId)
    }

    @Test
    fun `account clear invalidates resolver writes from the previous account`() {
        val staleGeneration = ContinueWatchingEnrichmentCache.generation.value

        ContinueWatchingEnrichmentCache.clearLocalState()

        assertFalse(
            ContinueWatchingEnrichmentCache.saveSnapshots(
                profileId = 1,
                source = WatchProgressSource.NUVIO_SYNC,
                generation = staleGeneration,
                nextUp = emptyList(),
                inProgress = emptyList(),
            ),
        )
        ContinueWatchingEnrichmentCache.clearAll(profileId = 1)
    }
}
