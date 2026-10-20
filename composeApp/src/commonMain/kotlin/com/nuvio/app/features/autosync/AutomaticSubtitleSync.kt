package com.nuvio.app.features.autosync

import com.nuvio.app.features.autosync.AutoSyncReferenceConsistency.Reference
import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.roundToInt

/**
 * Automatic variant of the manual Auto Sync feature: aligns the subtitle the user is playing
 * (the target) against other subtitles of the same video (references) and reports the constant
 * delay-only offset that the player should apply.
 *
 * The engine only needs cue timings, so references can be any subtitle with a parseable
 * timeline - in the desktop player they are the other add-on subtitles fetched for the current
 * item. References whose timing no independent source confirms (checked by
 * [AutoSyncReferenceConsistency]) are dropped before scoring; the highest-scoring trusted
 * alignment wins. All confidence gates (minimum score/margin, segment stability) live in
 * [AutoSyncTimelineRetimer].
 *
 * Named separately from Android's `AutomaticSubtitleSync` object, which lives in androidMain.
 */
internal object AutomaticDelaySyncEngine {
    internal data class ReferenceCues(
        val key: String,
        val cues: List<SubtitleSyncCue>,
        val isSdh: Boolean = false,
    )

    internal sealed interface Decision {
        /** No target cues, no references, or none alignable. */
        data object NotApplicable : Decision

        /** References existed but none matched with the required confidence. */
        data object NoConfidentMatch : Decision

        data class ApplyOffset(val offsetMs: Int) : Decision
    }

    private const val MIN_CUES = 4

    fun computeAlignment(
        targetCues: List<SubtitleSyncCue>,
        references: List<ReferenceCues>,
    ): Decision {
        if (references.isEmpty()) return Decision.NotApplicable
        val target = AutoSyncTimelineRetimer.normalizeExternalTimeline(targetCues)
        if (target.size < MIN_CUES) return Decision.NotApplicable
        val targetActivity = AutoSyncTimelineRetimer.prepareUnitActivity(target)
            ?: return Decision.NotApplicable

        val prepared = references.mapNotNull { candidate ->
            val cueList = AutoSyncTimelineRetimer.normalizeExternalTimeline(candidate.cues)
            if (cueList.size < MIN_CUES) return@mapNotNull null
            val activity = AutoSyncTimelineRetimer.prepareUnitActivity(cueList)
                ?: return@mapNotNull null
            Reference(
                key = candidate.key,
                activity = activity,
                cueCount = cueList.size,
            ) to candidate
        }
        if (prepared.isEmpty()) return Decision.NotApplicable

        val outlierKeys = AutoSyncReferenceConsistency.findOutliers(prepared.map { it.first })
        val trusted = prepared.filter { it.first.key !in outlierKeys }
        if (trusted.isEmpty()) return Decision.NoConfidentMatch

        var best: AutoSyncDelayOnlyAlignment? = null
        for ((reference, candidate) in trusted) {
            val allowAmbiguousMargin = AutoSyncTimelineRetimer.shouldRelaxDelayOnlyMargin(
                sdhReference = candidate.isSdh,
                referenceSize = reference.cueCount,
                targetSize = target.size,
            )
            val alignment = AutoSyncTimelineRetimer.findDelayOnlyAlignmentPrepared(
                referenceActivity = reference.activity,
                targetActivity = targetActivity,
                targetSize = target.size,
                allowAmbiguousMargin = allowAmbiguousMargin,
            ) ?: continue
            if (best == null || alignment.score > best!!.score) best = alignment
        }
        val winner = best ?: return Decision.NoConfidentMatch
        return Decision.ApplyOffset(winner.offsetMs.roundToInt())
    }
}