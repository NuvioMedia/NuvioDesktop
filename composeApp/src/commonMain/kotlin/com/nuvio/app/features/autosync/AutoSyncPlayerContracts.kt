package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSlot

internal data class AutoSyncSubtitleCandidate(
    val url: String,
    val language: String,
    val name: String? = null,
    val isForced: Boolean = false,
)

/**
 * Optional Android AutoSync capability layered beside PlayerEngineController.
 * Other platforms do not need to implement it.
 */
internal interface AutoSyncPlayerController {
    fun cancelForSubtitleSlot(slot: SubtitleSlot) {
        if (slot == SubtitleSlot.Primary) cancelForManualSubtitleDelay()
    }
    fun startSubtitleSlotAutoSync(slot: SubtitleSlot, url: String, startup: Boolean) {}
    fun setSubtitleSlotAutoSyncAppliedListener(listener: ((SubtitleSlot, String, Int) -> Unit)?) {}
    fun cancelForManualSubtitleDelay()
    fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>)
    fun setSubtitleUriWithAutoSync(url: String)
    fun setSubtitleUriWithSelectedAutoSync(url: String)
    fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    )
    fun setAutoSyncFailureListener(listener: ((AutoSyncFailure) -> Unit)?) {}
}

internal enum class AutoSyncFailure { NO_REFERENCE, FAILED }
