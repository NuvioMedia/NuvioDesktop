package com.nuvio.app.features.autosync

import com.nuvio.app.features.autosync.AutoSyncReferenceConsistency.Reference
import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoSyncDelayOnlyTest {
    @Test
    fun delayOnlyAlignmentRecoversConstantOffset() {
        val reference = irregularTimeline(220)
        val target = shift(reference, -4_200L)

        val alignment = assertNotNull(
            AutoSyncTimelineRetimer.findDelayOnlyAlignment(reference, target),
        )

        assertTrue(abs(alignment.offsetMs - 4_200.0) <= 500.0)
        assertTrue(alignment.score >= 0.78)
    }

    @Test
    fun delayOnlyAlignmentRejectsProgressiveDrift() {
        val reference = irregularTimeline(260)
        val scale = 25.0 / 23.976
        val target = reference.map { cue ->
            SubtitleSyncCue(
                startTimeMs = (cue.startTimeMs / scale).toLong(),
                endTimeMs = (cue.endTimeMs / scale).toLong(),
                text = cue.text,
            )
        }

        assertNull(AutoSyncTimelineRetimer.findDelayOnlyAlignment(reference, target))
        assertNull(
            AutoSyncTimelineRetimer.findDelayOnlyAlignment(
                reference = reference,
                target = target,
                allowAmbiguousMargin = true,
            ),
        )
    }

    @Test
    fun relaxedDelayOnlyAlignmentAcceptsDenseSdhReference() {
        val base = irregularTimeline(220)
        val reference = buildList {
            addAll(base)
            base.forEachIndexed { index, cue ->
                if (index % 2 == 0) {
                    val start = cue.endTimeMs + 250L
                    add(SubtitleSyncCue(start, start + 900L, "sdh extra $index"))
                }
            }
        }.sortedBy { it.startTimeMs }
        val target = shift(base, -900L)

        val alignment = assertNotNull(
            AutoSyncTimelineRetimer.findDelayOnlyAlignment(
                reference = reference,
                target = target,
                allowAmbiguousMargin = true,
            ),
        )

        assertTrue(abs(alignment.offsetMs - 900.0) <= 500.0)
        assertEquals(3, alignment.segmentsPassed)
    }

    @Test
    fun normalizeExternalTimelineSortsAndDeduplicates() {
        val duplicate = SubtitleSyncCue(5_000L, 6_000L, "same")
        val cues = listOf(
            SubtitleSyncCue(9_000L, 10_000L, "later"),
            duplicate,
            SubtitleSyncCue(1_000L, 2_000L, "earlier"),
            duplicate.copy(),
        )

        val normalized = AutoSyncTimelineRetimer.normalizeExternalTimeline(cues)

        assertEquals(listOf(1_000L, 5_000L, 9_000L), normalized.map { it.startTimeMs })
    }

    @Test
    fun shouldRelaxDelayOnlyMarginForSdhAndDenseReferences() {
        assertTrue(
            AutoSyncTimelineRetimer.shouldRelaxDelayOnlyMargin(
                sdhReference = true,
                referenceSize = 100,
                targetSize = 100,
            ),
        )
        assertTrue(
            AutoSyncTimelineRetimer.shouldRelaxDelayOnlyMargin(
                sdhReference = false,
                referenceSize = 150,
                targetSize = 100,
            ),
        )
        assertFalse(
            AutoSyncTimelineRetimer.shouldRelaxDelayOnlyMargin(
                sdhReference = false,
                referenceSize = 100,
                targetSize = 100,
            ),
        )
    }

    @Test
    fun referenceConsistencyDropsMistimedOutlier() {
        val base = irregularTimeline(220)
        val references = listOf(
            reference("a", base),
            reference("b", shift(base, 700L)),
            reference("c", shift(base, 40_000L)),
        )

        val outliers = AutoSyncReferenceConsistency.findOutliers(references)

        assertEquals(setOf("c"), outliers)
    }

    @Test
    fun referenceConsistencyNeedsThreeIndependentSources() {
        val base = irregularTimeline(220)
        val references = listOf(
            reference("a", base),
            reference("b", shift(base, 40_000L)),
        )

        assertTrue(AutoSyncReferenceConsistency.findOutliers(references).isEmpty())
    }

    @Test
    fun automaticSyncAppliesOffsetFromMatchingReference() {
        val referenceCues = irregularTimeline(220)
        val target = shift(referenceCues, -4_000L)

        val decision = AutomaticSubtitleSync.computeAlignment(
            targetCues = target,
            references = listOf(AutomaticSubtitleSync.ReferenceCues("ref", referenceCues)),
        )

        val applied = decision as? AutomaticSubtitleSync.Decision.ApplyOffset
        assertNotNull(applied)
        assertTrue(abs(applied.offsetMs - 4_000) <= 500)
    }

    @Test
    fun automaticSyncNotApplicableWithoutReferences() {
        val target = irregularTimeline(220)
        assertEquals(
            AutomaticSubtitleSync.Decision.NotApplicable,
            AutomaticSubtitleSync.computeAlignment(target, emptyList()),
        )
    }

    @Test
    fun automaticSyncDoesNotApplyForUnrelatedTimeline() {
        val target = irregularTimeline(220)
        val unrelated = repeatedCadenceTimeline(220)

        val decision = AutomaticSubtitleSync.computeAlignment(
            targetCues = target,
            references = listOf(AutomaticSubtitleSync.ReferenceCues("ref", unrelated)),
        )

        assertFalse(decision is AutomaticSubtitleSync.Decision.ApplyOffset)
    }

    private fun reference(key: String, cues: List<SubtitleSyncCue>): Reference {
        val activity = assertNotNull(AutoSyncTimelineRetimer.prepareUnitActivity(cues))
        return Reference(key = key, activity = activity, cueCount = cues.size)
    }

    private fun shift(cues: List<SubtitleSyncCue>, deltaMs: Long) = cues.map { cue ->
        cue.copy(startTimeMs = cue.startTimeMs + deltaMs, endTimeMs = cue.endTimeMs + deltaMs)
    }

    private fun irregularTimeline(count: Int): List<SubtitleSyncCue> {
        var start = 30_000L
        return (0 until count).map { index ->
            if (index > 0) start += 1_400L + ((index * 977L) % 4_300L)
            SubtitleSyncCue(start, start + 900L + ((index * 313L) % 1_700L), "irregular $index")
        }
    }

    private fun repeatedCadenceTimeline(count: Int): List<SubtitleSyncCue> {
        val cadence = longArrayOf(1_900L, 3_100L, 2_400L, 4_200L, 2_100L, 3_700L, 2_800L)
        var start = 90_000L
        return (0 until count).map { index ->
            if (index > 0) start += cadence[(index - 1) % cadence.size] + (index % 5) * 73L
            SubtitleSyncCue(start, start + 1_000L + (index % 4) * 190L, "cadence $index")
        }
    }
}