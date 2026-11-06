package com.nuvio.app.features.player

import com.nuvio.app.core.i18n.localizedNoSubtitleLinesFound
import com.nuvio.app.core.i18n.localizedSubtitleLinesLoadError
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.autosync.AutoSyncPlayerController
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

internal fun PlayerScreenRuntime.fetchAddonSubtitlesForActiveItem() {
    if (activeSourceUrl.startsWith("file:") && externalSubtitles.isNotEmpty()) {
        SubtitleRepository.clear()
        return
    }
    if (addonSubtitleFetchKey == null) return
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() } ?: return
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return
    SubtitleRepository.fetchAddonSubtitles(type, videoId)
}

internal fun PlayerScreenRuntime.setSubtitleDelay(delayMs: Int, slot: SubtitleSlot = SubtitleSlot.Primary) {
    if (slot == SubtitleSlot.Secondary) {
        (playerController as? AutoSyncPlayerController)?.cancelForSubtitleSlot(slot)
        secondarySubtitleDelayMs = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
        secondarySubtitleAutoSyncedUrl = null
        playerController?.setSubtitleSlotDelayMs(slot, secondarySubtitleDelayMs)
        return
    }
    (playerController as? AutoSyncPlayerController)?.cancelForManualSubtitleDelay() // AutoSync hook
    val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    subtitleDelayMs = clamped
    PlayerTrackPreferenceStorage.saveSubtitleDelayMs(playbackSession.videoId, clamped)
    playerController?.setSubtitleDelayMs(clamped)
}

internal fun PlayerScreenRuntime.loadSubtitleAutoSyncCues(force: Boolean = false) {
    val slot = activeSubtitleSlot
    val subtitle = (subtitleSelection(slot) as? SubtitleSelectionOption.Addon)?.subtitle ?: return
    fun state() = if (slot == SubtitleSlot.Primary) subtitleAutoSyncState else secondarySubtitleAutoSyncState
    fun update(value: SubtitleAutoSyncUiState) {
        if (slot == SubtitleSlot.Primary) subtitleAutoSyncState = value else secondarySubtitleAutoSyncState = value
    }
    if (!force && state().cues.isNotEmpty()) return
    if (isNetworkOffline()) return
    if (slot == SubtitleSlot.Primary) primarySubtitleCueJob?.cancel() else secondarySubtitleCueJob?.cancel()
    val identity = activePlaybackIdentity
    update(state().copy(isLoading = true, errorMessage = null))
    val job = scope.launch {
        try {
            val body = httpGetTextWithHeaders(
                url = subtitle.url,
                headers = sanitizePlaybackHeaders(activeSourceHeaders + externalSubtitles.firstOrNull { it.url == subtitle.url }?.headers.orEmpty()),
            )
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (identity != activePlaybackIdentity || (subtitleSelection(slot) as? SubtitleSelectionOption.Addon)?.subtitle?.selectionKey != subtitle.selectionKey) return@launch
            val cues = PlayerSubtitleCueParser.parse(body, subtitle.url)
            update(state().copy(cues = cues, isLoading = false,
                errorMessage = if (cues.isEmpty()) localizedNoSubtitleLinesFound() else null))
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            if (identity == activePlaybackIdentity && (subtitleSelection(slot) as? SubtitleSelectionOption.Addon)?.subtitle?.selectionKey == subtitle.selectionKey) update(state().copy(isLoading = false,
                errorMessage = error.message ?: localizedSubtitleLinesLoadError()))
        }
    }
    if (slot == SubtitleSlot.Primary) primarySubtitleCueJob = job else secondarySubtitleCueJob = job
}

internal fun PlayerScreenRuntime.captureSubtitleAutoSyncTime() {
    activeSubtitleAutoSyncState = activeSubtitleAutoSyncState.copy(
        capturedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
        errorMessage = null,
    )
    loadSubtitleAutoSyncCues()
}

internal fun PlayerScreenRuntime.applySubtitleAutoSyncCue(cue: SubtitleSyncCue) {
    val capturedPositionMs = activeSubtitleAutoSyncState.capturedPositionMs ?: return
    val newDelayMs = (capturedPositionMs - cue.startTimeMs - SUBTITLE_AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    setSubtitleDelay(newDelayMs, activeSubtitleSlot)
}
