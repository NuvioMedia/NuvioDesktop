package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlEpisodeItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopEpisodeNavigationTest {
    private val episodes = listOf(
        PlayerControlEpisodeItem(index = 0, id = "s2e1", season = 2, episode = 1),
        PlayerControlEpisodeItem(index = 1, id = "s1e2", season = 1, episode = 2),
        PlayerControlEpisodeItem(index = 3, id = "s1e1", season = 1, episode = 1),
    )

    @Test
    fun previousExistsForRawFirstItemAndCrossesSeasonBoundary() {
        val previous = episodes.withCurrent("s2e1").resolvePreviousEpisode()
        assertEquals("s1e2", previous?.id)
        assertEquals(1, previous?.index)
    }

    @Test
    fun firstEpisodeHasNoPreviousEvenWhenLastInAddonList() {
        assertNull(episodes.withCurrent("s1e1").resolvePreviousEpisode())
    }

    @Test
    fun preservesOriginalAddonIndexWhenResolvingWithinSeason() {
        val previous = episodes.withCurrent("s1e2").resolvePreviousEpisode()
        assertEquals("s1e1", previous?.id)
        assertEquals(3, previous?.index)
    }

    @Test
    fun noPreviousWithoutCurrentEpisode() {
        assertNull(episodes.resolvePreviousEpisode())
        assertNull(emptyList<PlayerControlEpisodeItem>().resolvePreviousEpisode())
    }

    private fun List<PlayerControlEpisodeItem>.withCurrent(id: String) =
        map { it.copy(isCurrent = it.id == id) }
}
