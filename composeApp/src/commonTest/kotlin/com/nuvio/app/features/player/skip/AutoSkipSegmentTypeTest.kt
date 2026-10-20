package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoSkipSegmentTypeTest {
    @Test
    fun mapsSupportedIntervalAliases() {
        assertEquals(AutoSkipSegmentType.INTRO, AutoSkipSegmentType.fromSkipIntervalType("opening"))
        assertEquals(AutoSkipSegmentType.INTRO, AutoSkipSegmentType.fromSkipIntervalType("mixed-op"))
        assertEquals(AutoSkipSegmentType.RECAP, AutoSkipSegmentType.fromSkipIntervalType("recap"))
        assertEquals(AutoSkipSegmentType.OUTRO, AutoSkipSegmentType.fromSkipIntervalType("ending"))
        assertEquals(AutoSkipSegmentType.OUTRO, AutoSkipSegmentType.fromSkipIntervalType("credits"))
        assertNull(AutoSkipSegmentType.fromSkipIntervalType("preview"))
    }

    @Test
    fun intervalKeyDistinguishesProviderTypeAndBounds() {
        val interval = SkipInterval(
            startTime = 12.5,
            endTime = 96.0,
            type = "intro",
            provider = "introdb",
        )

        assertEquals("introdb:intro:12.5:96.0", interval.autoSkipKey())
    }

    @Test
    fun resumedPlaybackConsumesOnlyIntervalsAlreadyPassed() {
        val intro = SkipInterval(0.0, 90.0, "intro", "introdb")
        val outro = SkipInterval(1_200.0, 1_260.0, "outro", "introdb")

        val completed = listOf(intro, outro).autoSkipKeysCompletedBy(positionMs = 600_000L)

        assertEquals(setOf(intro.autoSkipKey()), completed)
    }

    @Test
    fun freshPlaybackDoesNotPreconsumeIntervals() {
        val intro = SkipInterval(0.0, 90.0, "intro", "introdb")

        assertTrue(listOf(intro).autoSkipKeysCompletedBy(positionMs = 0L).isEmpty())
    }
    @Test
    fun movieCreditsSkipToThePostCreditsSceneWithoutSkippingTheScene() {
        val credits = SkipInterval(900.0, 960.0, "movie-credits", "introdb")
        val scene = SkipInterval(970.0, 1_000.0, "post-credits", "introdb")
        val intervals = listOf(credits, scene)

        assertTrue(credits.shouldAutoSkip(setOf(AutoSkipSegmentType.MOVIE_CREDITS)))
        assertFalse(credits.shouldAutoSkip(setOf(AutoSkipSegmentType.OUTRO)))
        assertEquals(InternalSkipAction(970_000L, true), credits.internalSkipAction(intervals, 1_020_000L))
        assertNull(scene.internalSkipAction(intervals, 1_020_000L))
        assertFalse(scene.shouldAutoSkip(AutoSkipSegmentType.entries.toSet()))
    }

    @Test
    fun seekingBackIntoCreditsConsumesTheIntervalForAutoSkip() {
        val credits = SkipInterval(900.0, 960.0, "movie-credits", "introdb")
        val scene = SkipInterval(970.0, 1_000.0, "post-credits", "introdb")

        assertEquals(listOf(credits), listOf(credits, scene).intervalsAtSeekPositions(980_000L, 930_000L))
        assertTrue(listOf(credits, scene).intervalsAtSeekPositions(980_000L, 990_000L).isEmpty())
    }
}
