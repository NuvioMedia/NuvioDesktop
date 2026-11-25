package com.nuvio.app.features.discordrpc

import com.nuvio.app.core.ui.PresenceSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordActivityMappingTest {
    @Test
    fun textFieldsStayWithinDiscordLimits() {
        assertEquals(128, "x".repeat(300).toDiscordActivityText()?.length)
        assertEquals(2, "x".toDiscordActivityText()?.length)
        assertNull("   ".toDiscordActivityText())
        assertEquals("Dune", " Dune ".toDiscordActivityText())
    }

    @Test
    fun truncationNeverSplitsASurrogatePair() {
        val split = ("x".repeat(126) + "\uD83D\uDE00B").toDiscordActivityText()
        assertEquals("x".repeat(126) + "…", split)
        val kept = ("x".repeat(125) + "\uD83D\uDE00" + "y".repeat(10)).toDiscordActivityText()
        assertEquals("x".repeat(125) + "\uD83D\uDE00…", kept)
        assertEquals(128, kept?.length)
    }

    @Test
    fun onlyShortHttpImagesAreSent() {
        assertEquals("https://img.example/p.jpg", "https://img.example/p.jpg".toDiscordActivityImage())
        val longestAccepted = "https://img.example/" + "a".repeat(300 - "https://img.example/".length)
        assertEquals(longestAccepted, longestAccepted.toDiscordActivityImage())
        assertNull((longestAccepted + "a").toDiscordActivityImage())
        assertNull("data:image/png;base64,AAAA".toDiscordActivityImage())
        assertNull(null.toDiscordActivityImage())
    }

    @Test
    fun playerActivityWithLongMetadataIsClamped() {
        val activity = PresenceSnapshot.Player(
            title = "T".repeat(200),
            episodeLabel = "S1E2 - " + "E".repeat(200),
            posterUrl = "https://img.example/" + "a".repeat(300),
            isPlaying = true,
            positionMs = 10_000L,
            durationMs = 60_000L,
        ).toDiscordActivity()
        assertEquals(128, activity.details?.length)
        assertTrue((activity.state?.length ?: 0) <= 128)
        assertNull(activity.assets)
        assertNotNull(activity.timestamps)
    }

    @Test
    fun pausedSuffixSurvivesALongEpisodeLabel() {
        val playing = PresenceSnapshot.Player(
            title = "Dune",
            episodeLabel = "S1E2 - " + "E".repeat(200),
            posterUrl = null,
            isPlaying = true,
            positionMs = 0L,
            durationMs = 60_000L,
        )
        val playingState = playing.toDiscordActivity().state
        val pausedState = playing.copy(isPlaying = false).toDiscordActivity().state
        assertNotNull(pausedState)
        assertTrue(pausedState.endsWith("… • Paused"))
        assertTrue(pausedState.length <= 128)
        assertFalse(playingState.orEmpty().endsWith("Paused"))
        assertEquals("S1, E2 • Paused", playing.copy(episodeLabel = "S1E2", isPlaying = false).toDiscordActivity().state)
        assertEquals("Paused", playing.copy(episodeLabel = null, isPlaying = false).toDiscordActivity().state)
    }
}
