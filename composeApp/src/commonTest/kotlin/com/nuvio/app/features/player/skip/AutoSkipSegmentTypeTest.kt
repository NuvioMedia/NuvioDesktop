package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
