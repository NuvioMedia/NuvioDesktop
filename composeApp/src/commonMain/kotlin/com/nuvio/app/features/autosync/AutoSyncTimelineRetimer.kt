package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Text-independent whole-timeline subtitle alignment, ported from Nuvio Reshaped's AutoSync and
 * trimmed to the delay-only fast path that desktop playback needs.
 *
 * The aligner builds coarse (500 ms) and fine (100 ms) "activity" timelines from the cue start/end
 * times of the target (the subtitle the user plays) and of a reference (another subtitle, ideally
 * timed to the same video). It searches the coarse offset space, refines the winner on the fine
 * bins, and gates the result on a three-segment stability check so only confident, constant-delay
 * corrections are reported. The result is a single offset in ms applied through the player's
 * subtitle delay.
 *
 * The file is deliberately commonMain and player-independent so the algorithm can be unit-tested
 * without networking or playback state.
 */
internal object AutoSyncTimelineRetimer {
    private const val MIN_CUES = 4

    // Whole-timeline subtitle activity correlation. This is deliberately global:
    // mid-film cuts/splits are rejected rather than growing another piecewise synchronization layer.
    private const val ACTIVITY_COARSE_BIN_MS = 500L
    private const val ACTIVITY_FINE_BIN_MS = 100L
    private const val ACTIVITY_MAX_OFFSET_MS = 180_000L
    private const val ACTIVITY_FINE_RADIUS_MS = 750L
    // Dense dialogue saturates 500 ms coarse bins, so neighbouring offsets can score within a
    // hair of each other and the coarse argmax lands on a plateau edge. Fine refinement then
    // spans the whole contiguous plateau (capped) instead of only the argmax's own radius.
    private const val ACTIVITY_COARSE_PLATEAU_SCORE_EPSILON = 0.01
    private const val ACTIVITY_COARSE_PLATEAU_MAX_EXTENT_MS = 3_000L
    // Coverage scoring stays flat across such a plateau even at 100 ms bins. Fine offsets within
    // this score of the fine best are separated by how closely target cue starts land on
    // reference cue starts, which is sharp to a single bin.
    private const val ACTIVITY_FINE_TIE_SCORE_EPSILON = 0.005
    private const val ACTIVITY_ONSET_MATCH_TOLERANCE_MS = 250L
    private const val ACTIVITY_MAX_CUE_DURATION_MS = 20_000L
    private const val ACTIVITY_MAX_TIMELINE_MS = 8L * 60L * 60L * 1_000L
    private const val ACTIVITY_MIN_SCALE = 0.94
    private const val ACTIVITY_MAX_SCALE = 1.06

    // Cheap delay-only fast path. It always APPLIES scale=1.0; the segment drift tolerance
    // merely allows near-1.0 timelines to qualify when one constant delay remains visually valid.
    private const val DELAY_ONLY_MIN_CUES = 4
    private const val DELAY_ONLY_MIN_SCORE = 0.78
    private const val DELAY_ONLY_MIN_MARGIN = 0.02
    private const val DELAY_ONLY_MIN_SEGMENT_SCORE = 0.68
    private const val DELAY_ONLY_SEGMENT_SEARCH_RADIUS_MS = 1_000L
    private const val DELAY_ONLY_MAX_SEGMENT_OFFSET_DELTA_MS = 500L
    private const val DELAY_ONLY_DISTINCT_OFFSET_MS = 3_000L
    private const val SMALL_SAMPLE_CUE_LIMIT = 8

    // A target spanning well under the reference (e.g. a CD2 subtitle) can sit anywhere inside
    // it, so its offset search covers the whole reference instead of only ACTIVITY_MAX_OFFSET_MS.
    private const val PARTIAL_TARGET_SPAN_RATIO = 0.75

    internal fun normalizeExternalTimeline(
        cues: List<SubtitleSyncCue>,
    ): List<SubtitleSyncCue> {
        if (cues.size < 2) return cues

        val sorted = cues.sortedBy { it.startTimeMs }
        val seen = HashSet<ExternalCueKey>()
        val normalized = ArrayList<SubtitleSyncCue>(sorted.size)
        for (cue in sorted) {
            val key = ExternalCueKey(
                startTimeMs = cue.startTimeMs,
                endTimeMs = cue.endTimeMs,
                text = cue.text,
            )
            if (seen.add(key)) normalized += cue
        }
        return normalized
    }

    /**
     * SDH and over-segmented references (1.5x the target's cues or more) legitimately produce
     * ambiguous delay-only margins, so the margin gate is relaxed for them.
     */
    internal fun shouldRelaxDelayOnlyMargin(
        sdhReference: Boolean,
        referenceSize: Int,
        targetSize: Int,
    ): Boolean = sdhReference || referenceSize.toLong() * 2L >= targetSize.toLong() * 3L

    internal fun prepareUnitActivity(
        cues: List<SubtitleSyncCue>,
    ): PreparedActivity? {
        if (cues.size < MIN_CUES) return null
        val coarse = buildActivityTimeline(cues, 1.0, ACTIVITY_COARSE_BIN_MS) ?: return null
        val fine = buildActivityTimeline(cues, 1.0, ACTIVITY_FINE_BIN_MS) ?: return null
        val starts = LongArray(cues.size) { index -> cues[index].startTimeMs.coerceAtLeast(0L) }
        starts.sort()
        return PreparedActivity(coarse = coarse, fine = fine, starts = starts)
    }

    internal fun findDelayOnlyAlignment(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        allowAmbiguousMargin: Boolean = false,
    ): AutoSyncDelayOnlyAlignment? {
        if (reference.size < DELAY_ONLY_MIN_CUES || target.size < DELAY_ONLY_MIN_CUES) return null
        val referenceActivity = prepareUnitActivity(reference) ?: return null
        val targetActivity = prepareUnitActivity(target) ?: return null
        return findDelayOnlyAlignmentPrepared(
            referenceActivity = referenceActivity,
            targetActivity = targetActivity,
            targetSize = target.size,
            allowAmbiguousMargin = allowAmbiguousMargin,
        )
    }

    internal fun findDelayOnlyAlignmentPrepared(
        referenceActivity: PreparedActivity,
        targetActivity: PreparedActivity,
        targetSize: Int,
        allowAmbiguousMargin: Boolean = false,
        cancellationCheck: (() -> Unit)? = null,
    ): AutoSyncDelayOnlyAlignment? {
        val referenceCoarse = referenceActivity.coarse
        val targetCoarse = targetActivity.coarse

        val offsetRange = coarseOffsetRange(referenceCoarse, targetCoarse)
        val candidates = ArrayList<ActivityCandidate>()
        for (offsetBins in offsetRange) {
            if ((offsetBins - offsetRange.first) % 64 == 0) cancellationCheck?.invoke()
            val score = scoreActivityOffset(referenceCoarse, targetCoarse, offsetBins)
                ?: continue
            candidates += ActivityCandidate(
                scale = 1.0,
                interceptMs = offsetBins * ACTIVITY_COARSE_BIN_MS,
                score = score,
            )
        }

        val coarseSeed = buildDelayOnlySearchSeed(candidates) ?: return null
        val coarse = coarseSeed.coarse
        val margin = coarseSeed.margin
        val marginAccepted = allowAmbiguousMargin || margin >= DELAY_ONLY_MIN_MARGIN
        if (!marginAccepted) return null

        val referenceFine = referenceActivity.fine
        val targetFine = targetActivity.fine
        val best = coarseSeed.fine
            ?: fineActivityBest(
                referenceFine = referenceFine,
                targetFine = targetFine,
                referenceStarts = referenceActivity.starts,
                targetStarts = targetActivity.starts,
                scale = 1.0,
                plateauStartMs = coarseSeed.plateauStartMs,
                plateauEndMs = coarseSeed.plateauEndMs,
                cancellationCheck = cancellationCheck,
            )
            ?: coarse

        if (best.score < DELAY_ONLY_MIN_SCORE) return null

        val globalOffsetBins =
            (best.interceptMs.toDouble() / ACTIVITY_FINE_BIN_MS.toDouble()).roundToInt()
        val localRadiusBins =
            (DELAY_ONLY_SEGMENT_SEARCH_RADIUS_MS / ACTIVITY_FINE_BIN_MS).toInt()
        val maxDeltaBins =
            (DELAY_ONLY_MAX_SEGMENT_OFFSET_DELTA_MS / ACTIVITY_FINE_BIN_MS).toInt()

        var availableSegments = 0
        var passedSegments = 0
        for (segment in 0..2) {
            cancellationCheck?.invoke()
            if (allowAmbiguousMargin) {
                val targetCoverage = targetActivityCoverageAtOffsetSegment(
                    reference = referenceFine,
                    target = targetFine,
                    offsetBins = globalOffsetBins,
                    segment = segment,
                ) ?: continue
                availableSegments++
                if (targetCoverage >= DELAY_ONLY_MIN_SEGMENT_SCORE) passedSegments++
                continue
            }

            // The segment agrees when an offset within maxDelta of the global one scores as
            // well as the segment's best, up to the coverage tie epsilon: on a flat coverage
            // plateau the segment argmax is an arbitrary edge, not evidence of drift.
            var segmentBestScore = Double.NEGATIVE_INFINITY
            var segmentNearScore = Double.NEGATIVE_INFINITY
            var hasScore = false
            for (delta in -localRadiusBins..localRadiusBins) {
                val candidateOffsetBins = globalOffsetBins + delta
                val score = scoreActivityOffsetSegment(
                    reference = referenceFine,
                    target = targetFine,
                    offsetBins = candidateOffsetBins,
                    segment = segment,
                ) ?: continue
                hasScore = true
                segmentBestScore = max(segmentBestScore, score)
                if (abs(delta) <= maxDeltaBins) segmentNearScore = max(segmentNearScore, score)
            }

            if (!hasScore) continue
            availableSegments++
            if (
                segmentBestScore >= DELAY_ONLY_MIN_SEGMENT_SCORE &&
                segmentNearScore >= segmentBestScore - ACTIVITY_FINE_TIE_SCORE_EPSILON
            ) {
                passedSegments++
            }
        }

        val requiredSegments = if (targetSize < SMALL_SAMPLE_CUE_LIMIT) 2 else 3
        if (availableSegments < requiredSegments || passedSegments < requiredSegments) return null

        return AutoSyncDelayOnlyAlignment(
            offsetMs = best.interceptMs.toDouble(),
            score = best.score,
            margin = margin,
            segmentsPassed = passedSegments,
        )
    }

    // ---- coarse / fine activity search ------------------------------------

    /**
     * Coarse offsets searched for one pair: ±[ACTIVITY_MAX_OFFSET_MS] normally, widened to every
     * placement inside the reference when the target covers only part of it (CD1/CD2 subtitles).
     */
    private fun coarseOffsetRange(
        reference: ActivityTimeline,
        target: ActivityTimeline,
    ): IntRange {
        val baseBins = (ACTIVITY_MAX_OFFSET_MS / ACTIVITY_COARSE_BIN_MS).toInt()
        val referenceSpan = reference.lastActive - reference.firstActive
        val targetSpan = target.lastActive - target.firstActive
        if (targetSpan >= referenceSpan * PARTIAL_TARGET_SPAN_RATIO) return -baseBins..baseBins

        return min(-baseBins, reference.firstActive - target.firstActive - baseBins)..
            max(baseBins, reference.lastActive - target.lastActive + baseBins)
    }

    private fun buildDelayOnlySearchSeed(
        candidates: List<ActivityCandidate>,
    ): DelayOnlySearchSeed? {
        val coarse = candidates.maxByOrNull { it.score } ?: return null
        val secondDistinct = candidates.asSequence()
            .filter { candidate ->
                abs(candidate.interceptMs - coarse.interceptMs) >=
                    DELAY_ONLY_DISTINCT_OFFSET_MS
            }
            .maxByOrNull { it.score }
        val plateau = coarsePlateauMs(candidates, coarse)
        return DelayOnlySearchSeed(
            coarse = coarse,
            margin = coarse.score - (secondDistinct?.score ?: 0.0),
            fine = null,
            plateauStartMs = plateau.first,
            plateauEndMs = plateau.last,
        )
    }

    /**
     * Contiguous coarse offsets around [best] (same scale) scoring within
     * [ACTIVITY_COARSE_PLATEAU_SCORE_EPSILON] of it, capped at
     * [ACTIVITY_COARSE_PLATEAU_MAX_EXTENT_MS] on each side. [candidates] of one scale are in
     * ascending offset order, as every coarse sweep produces them.
     */
    private fun coarsePlateauMs(
        candidates: List<ActivityCandidate>,
        best: ActivityCandidate,
    ): LongRange {
        val bestIndex = candidates.indexOfFirst { it === best }
        if (bestIndex < 0) return best.interceptMs..best.interceptMs
        val floor = best.score - ACTIVITY_COARSE_PLATEAU_SCORE_EPSILON

        fun extent(step: Int): Long {
            var edge = best.interceptMs
            var index = bestIndex + step
            while (index in candidates.indices) {
                val candidate = candidates[index]
                if (
                    candidate.scale != best.scale ||
                    candidate.interceptMs != edge + step * ACTIVITY_COARSE_BIN_MS ||
                    candidate.score < floor ||
                    abs(candidate.interceptMs - best.interceptMs) >
                    ACTIVITY_COARSE_PLATEAU_MAX_EXTENT_MS
                ) {
                    break
                }
                edge = candidate.interceptMs
                index += step
            }
            return edge
        }

        return extent(-1)..extent(1)
    }

    /**
     * Best fine-bin offset from the plateau start minus the fine radius to its end plus it.
     * Offsets whose coverage score ties the best within [ACTIVITY_FINE_TIE_SCORE_EPSILON] are
     * ranked by cue-start agreement, then by score.
     */
    private fun fineActivityBest(
        referenceFine: ActivityTimeline,
        targetFine: ActivityTimeline,
        referenceStarts: LongArray,
        targetStarts: LongArray,
        scale: Double,
        plateauStartMs: Long,
        plateauEndMs: Long,
        cancellationCheck: (() -> Unit)?,
    ): ActivityCandidate? {
        val candidates = ArrayList<ActivityCandidate>()
        var fineBest: ActivityCandidate? = null
        var offsetMs = plateauStartMs - ACTIVITY_FINE_RADIUS_MS
        while (offsetMs <= plateauEndMs + ACTIVITY_FINE_RADIUS_MS) {
            cancellationCheck?.invoke()
            val offsetBins = (offsetMs.toDouble() / ACTIVITY_FINE_BIN_MS.toDouble()).roundToInt()
            val score = scoreActivityOffset(referenceFine, targetFine, offsetBins)
            if (score != null) {
                val candidate = ActivityCandidate(
                    scale = scale,
                    interceptMs = offsetBins * ACTIVITY_FINE_BIN_MS,
                    score = score,
                )
                candidates += candidate
                val current = fineBest
                if (current == null || candidate.score > current.score) fineBest = candidate
            }
            offsetMs += ACTIVITY_FINE_BIN_MS
        }
        val best = fineBest ?: return null

        var chosen = best
        var chosenOnsets = -1.0
        for (candidate in candidates) {
            if (candidate.score < best.score - ACTIVITY_FINE_TIE_SCORE_EPSILON) continue
            cancellationCheck?.invoke()
            val onsets = onsetAgreement(
                referenceStarts = referenceStarts,
                targetStarts = targetStarts,
                scale = scale,
                interceptMs = candidate.interceptMs,
            )
            if (
                onsets > chosenOnsets ||
                (onsets == chosenOnsets && candidate.score > chosen.score)
            ) {
                chosen = candidate
                chosenOnsets = onsets
            }
        }
        return chosen
    }

    /**
     * How well target cue starts, mapped by [scale] and [interceptMs], land on reference starts:
     * each contributes 1 at an exact hit, falling linearly to 0 at
     * [ACTIVITY_ONSET_MATCH_TOLERANCE_MS] from the nearest reference start.
     */
    private fun onsetAgreement(
        referenceStarts: LongArray,
        targetStarts: LongArray,
        scale: Double,
        interceptMs: Long,
    ): Double {
        if (referenceStarts.isEmpty()) return 0.0
        val tolerance = ACTIVITY_ONSET_MATCH_TOLERANCE_MS
        var agreement = 0.0
        var cursor = 0
        for (start in targetStarts) {
            val mapped = (start * scale).roundToLong() + interceptMs
            while (cursor < referenceStarts.size && referenceStarts[cursor] < mapped - tolerance) {
                cursor++
            }
            if (cursor == referenceStarts.size) break
            var nearest = Long.MAX_VALUE
            var index = cursor
            while (index < referenceStarts.size && referenceStarts[index] <= mapped + tolerance) {
                nearest = min(nearest, abs(referenceStarts[index] - mapped))
                index++
            }
            if (nearest <= tolerance) {
                agreement += (tolerance - nearest).toDouble() / tolerance.toDouble()
            }
        }
        return agreement
    }

    // ---- activity timelines -----------------------------------------------

    private fun buildActivityTimeline(
        cues: List<SubtitleSyncCue>,
        scale: Double,
        binMs: Long,
    ): ActivityTimeline? {
        if (!scale.isFinite() || scale !in ACTIVITY_MIN_SCALE..ACTIVITY_MAX_SCALE) return null
        if (cues.isEmpty() || binMs <= 0L) return null

        val scaled = ArrayList<Pair<Long, Long>>(cues.size)
        var maxEndMs = 0L
        for (cue in cues) {
            val safeStart = cue.startTimeMs.coerceAtLeast(0L)
            val safeEnd = cue.endTimeMs
                .coerceAtLeast(safeStart + 1L)
                .coerceAtMost(safeStart + ACTIVITY_MAX_CUE_DURATION_MS)
            val start = (safeStart * scale).roundToLong().coerceAtLeast(0L)
            val end = (safeEnd * scale).roundToLong().coerceAtLeast(start + 1L)
            if (end > ACTIVITY_MAX_TIMELINE_MS) return null
            scaled += start to end
            maxEndMs = max(maxEndMs, end)
        }

        val binCount = max(1, ceil((maxEndMs + 1L).toDouble() / binMs.toDouble()).toInt())
        val bins = BooleanArray(binCount)
        for ((start, end) in scaled) {
            val first = (start / binMs).toInt().coerceIn(0, binCount - 1)
            val lastExclusive = ceil(end.toDouble() / binMs.toDouble())
                .toInt()
                .coerceIn(first + 1, binCount)
            for (index in first until lastExclusive) bins[index] = true
        }

        var activeCount = 0
        for (active in bins) if (active) activeCount++
        if (activeCount == 0) return null

        val activeIndexes = IntArray(activeCount)
        val prefix = IntArray(binCount + 1)
        val packed = LongArray((binCount + 63) ushr 6)
        var cursor = 0
        for (index in bins.indices) {
            if (bins[index]) {
                activeIndexes[cursor++] = index
                packed[index ushr 6] =
                    packed[index ushr 6] or (1L shl (index and 63))
                prefix[index + 1] = prefix[index] + 1
            } else {
                prefix[index + 1] = prefix[index]
            }
        }
        return ActivityTimeline(
            bins = bins,
            packed = packed,
            activeIndexes = activeIndexes,
            prefix = prefix,
            firstActive = activeIndexes.first(),
            lastActive = activeIndexes.last(),
        )
    }

    private fun targetActivityCoverageAtOffsetSegment(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
        segment: Int,
    ): Double? {
        if (segment !in 0..2) return null
        val activeSpan = target.lastActive - target.firstActive + 1
        if (activeSpan <= 0) return null

        val segmentStart = target.firstActive + activeSpan * segment / 3
        val segmentEnd = if (segment == 2) {
            target.lastActive
        } else {
            target.firstActive + activeSpan * (segment + 1) / 3 - 1
        }
        if (segmentEnd < segmentStart) return null

        val visibleTarget =
            target.prefix[segmentEnd + 1] - target.prefix[segmentStart]
        if (visibleTarget <= 0) return null
        val intersection = countShiftedActivityIntersection(
            reference = reference,
            target = target,
            offsetBins = offsetBins,
            sourceStart = segmentStart,
            sourceEnd = segmentEnd,
        )
        return intersection.toDouble() / visibleTarget.toDouble()
    }

    private fun scoreActivityOffsetSegment(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
        segment: Int,
    ): Double? {
        if (segment !in 0..2) return null
        val activeSpan = target.lastActive - target.firstActive + 1
        if (activeSpan <= 0) return null

        val segmentStart = target.firstActive + activeSpan * segment / 3
        val segmentEnd = if (segment == 2) {
            target.lastActive
        } else {
            target.firstActive + activeSpan * (segment + 1) / 3 - 1
        }
        if (segmentEnd < segmentStart) return null

        val visibleTarget =
            target.prefix[segmentEnd + 1] - target.prefix[segmentStart]
        if (visibleTarget <= 0) return null
        val intersection = countShiftedActivityIntersection(
            reference = reference,
            target = target,
            offsetBins = offsetBins,
            sourceStart = segmentStart,
            sourceEnd = segmentEnd,
        )

        val referenceWindowStart = max(0, segmentStart + offsetBins)
        val referenceWindowEnd = min(reference.bins.lastIndex, segmentEnd + offsetBins)
        if (referenceWindowEnd < referenceWindowStart) return null
        val referenceInWindow =
            reference.prefix[referenceWindowEnd + 1] - reference.prefix[referenceWindowStart]
        if (referenceInWindow <= 0) return null

        val precision = intersection.toDouble() / visibleTarget.toDouble()
        val recall = intersection.toDouble() / referenceInWindow.toDouble()
        return precision * 0.72 + recall * 0.28
    }

    private fun scoreActivityOffset(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
    ): Double? {
        val sourceStart = max(0, -offsetBins)
        val sourceEnd = min(target.bins.lastIndex, reference.bins.lastIndex - offsetBins)
        if (sourceEnd < sourceStart) return null
        val visibleTarget = target.prefix[sourceEnd + 1] - target.prefix[sourceStart]
        if (visibleTarget <= 0) return null

        val intersection = countShiftedActivityIntersection(
            reference = reference,
            target = target,
            offsetBins = offsetBins,
            sourceStart = sourceStart,
            sourceEnd = sourceEnd,
        )

        val referenceWindowStart = max(0, target.firstActive + offsetBins)
        val referenceWindowEnd = min(reference.bins.lastIndex, target.lastActive + offsetBins)
        if (referenceWindowEnd < referenceWindowStart) return null
        val referenceInWindow =
            reference.prefix[referenceWindowEnd + 1] - reference.prefix[referenceWindowStart]
        if (referenceInWindow <= 0) return null

        val precision = intersection.toDouble() / visibleTarget.toDouble()
        val recall = intersection.toDouble() / referenceInWindow.toDouble()
        val visibility = visibleTarget.toDouble() / target.activeIndexes.size.toDouble()
        return (precision * 0.72 + recall * 0.28) *
            (0.85 + 0.15 * visibility.coerceIn(0.0, 1.0))
    }

    private fun countShiftedActivityIntersection(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
        sourceStart: Int,
        sourceEnd: Int,
    ): Int {
        if (sourceEnd < sourceStart) return 0

        val firstWord = sourceStart ushr 6
        val lastWord = sourceEnd ushr 6
        var intersection = 0

        for (wordIndex in firstWord..lastWord) {
            val alignedReference = packedActivityWindow(
                packed = reference.packed,
                startBit = (wordIndex shl 6) + offsetBins,
            )
            val firstBit = if (wordIndex == firstWord) sourceStart and 63 else 0
            val lastBit = if (wordIndex == lastWord) sourceEnd and 63 else 63
            val mask = activityRangeMask(firstBit, lastBit)
            intersection +=
                (target.packed[wordIndex] and alignedReference and mask).countOneBits()
        }

        return intersection
    }

    private fun packedActivityWindow(
        packed: LongArray,
        startBit: Int,
    ): Long {
        val wordIndex = startBit shr 6
        val bitOffset = startBit and 63
        val low = if (wordIndex in packed.indices) packed[wordIndex] else 0L
        if (bitOffset == 0) return low

        val highIndex = wordIndex + 1
        val high = if (highIndex in packed.indices) packed[highIndex] else 0L
        return (low ushr bitOffset) or (high shl (64 - bitOffset))
    }

    private fun activityRangeMask(
        firstBit: Int,
        lastBit: Int,
    ): Long {
        val lower = -1L shl firstBit
        val upper = if (lastBit == 63) -1L else (1L shl (lastBit + 1)) - 1L
        return lower and upper
    }

    private data class ExternalCueKey(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val text: String,
    )

    internal data class ActivityTimeline(
        val bins: BooleanArray,
        val packed: LongArray,
        val activeIndexes: IntArray,
        val prefix: IntArray,
        val firstActive: Int,
        val lastActive: Int,
    )

    internal class PreparedActivity(
        val coarse: ActivityTimeline,
        val fine: ActivityTimeline,
        /** Sorted cue start times, for onset tie-breaks between equally covering offsets. */
        val starts: LongArray,
    )

    internal data class ActivityCandidate(
        val scale: Double,
        val interceptMs: Long,
        val score: Double,
    )

    internal data class DelayOnlySearchSeed(
        val coarse: ActivityCandidate,
        val margin: Double,
        val fine: ActivityCandidate?,
        /** Coarse score plateau around [coarse]; fine refinement covers all of it. */
        val plateauStartMs: Long = coarse.interceptMs,
        val plateauEndMs: Long = coarse.interceptMs,
    )
}

internal data class AutoSyncDelayOnlyAlignment(
    val offsetMs: Double,
    val score: Double,
    val margin: Double,
    val segmentsPassed: Int,
)