package com.nuvio.app.features.player.skip

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerEpisodeNavigationRulesTest {
    private val episodes = listOf(
        episode(id = "s2e1", season = 2, number = 1),
        episode(id = "s1e2", season = 1, number = 2),
        MetaVideo(id = "special", title = "Special"),
        episode(id = "s1e1", season = 1, number = 1),
    )

    @Test
    fun resolvesPreviousEpisodeInPlaybackOrder() {
        val previous = PlayerNextEpisodeRules.resolvePreviousEpisode(
            videos = episodes,
            currentSeason = 1,
            currentEpisode = 2,
        )

        assertEquals("s1e1", previous?.id)
    }

    @Test
    fun resolvesPreviousEpisodeAcrossSeasonBoundary() {
        val previous = PlayerNextEpisodeRules.resolvePreviousEpisode(
            videos = episodes,
            currentSeason = 2,
            currentEpisode = 1,
        )

        assertEquals("s1e2", previous?.id)
    }

    @Test
    fun returnsNullBeforeFirstEpisodeOrWhenCurrentEpisodeIsUnknown() {
        assertNull(PlayerNextEpisodeRules.resolvePreviousEpisode(episodes, 1, 1))
        assertNull(PlayerNextEpisodeRules.resolvePreviousEpisode(episodes, 9, 9))
        assertNull(PlayerNextEpisodeRules.resolvePreviousEpisode(episodes, null, 1))
    }

    @Test
    fun nextEpisodeResolutionStillUsesTheSameOrdering() {
        assertEquals("s1e2", PlayerNextEpisodeRules.resolveNextEpisode(episodes, 1, 1)?.id)
        assertEquals("s2e1", PlayerNextEpisodeRules.resolveNextEpisode(episodes, 1, 2)?.id)
        assertNull(PlayerNextEpisodeRules.resolveNextEpisode(episodes, 2, 1))
    }

    private fun episode(id: String, season: Int, number: Int) = MetaVideo(
        id = id,
        title = id,
        season = season,
        episode = number,
    )
}
