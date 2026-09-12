package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlEpisodeItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MprisPreviousActionTest {
    private val episodes = listOf(
        PlayerControlEpisodeItem(index = 0, id = "s2e1", season = 2, episode = 1),
        PlayerControlEpisodeItem(index = 1, id = "s1e2", season = 1, episode = 2),
        PlayerControlEpisodeItem(index = 3, id = "s1e1", season = 1, episode = 1),
    )

    @Test
    fun navigatesToPreviousEpisodeAtOrBelowThresholdWithOriginalIndex() {
        val action = resolveMprisPreviousAction(3_000L, episodes.withCurrent("s2e1"))

        assertTrue(action is MprisPreviousAction.SelectEpisode)
        assertEquals(1, action.index)
    }

    @Test
    fun restartsCurrentEpisodeAboveThresholdInsteadOfNavigating() {
        val action = resolveMprisPreviousAction(30_000L, episodes.withCurrent("s2e1"))

        assertTrue(action is MprisPreviousAction.RestartCurrent)
    }

    @Test
    fun logicalFirstEpisodeHasNoPreviousAction() {
        assertTrue(resolveMprisPreviousAction(0L, episodes.withCurrent("s1e1")) is MprisPreviousAction.NoPrevious)
        assertTrue(resolveMprisPreviousAction(30_000L, episodes.withCurrent("s1e1")) is MprisPreviousAction.NoPrevious)
    }

    @Test
    fun missingCurrentEpisodeHasNoPreviousAction() {
        assertTrue(resolveMprisPreviousAction(0L, episodes) is MprisPreviousAction.NoPrevious)
        assertTrue(resolveMprisPreviousAction(0L, emptyList()) is MprisPreviousAction.NoPrevious)
    }

    private fun List<PlayerControlEpisodeItem>.withCurrent(id: String) =
        map { it.copy(isCurrent = it.id == id) }
}
