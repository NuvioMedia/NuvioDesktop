package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.core.i18n.localizedAutoSyncApplied
import com.nuvio.app.core.i18n.localizedAutoSyncNoMatch
import com.nuvio.app.core.i18n.localizedNoSubtitleLinesFound
import com.nuvio.app.core.i18n.localizedSubtitleLinesLoadError
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.autosync.AutomaticSubtitleSync
import kotlinx.coroutines.launch

private val autoSyncLog = Logger.withTag("SubtitleAutoSync")

internal fun PlayerScreenRuntime.fetchAddonSubtitlesForActiveItem() {
    if (activeSourceUrl.startsWith("file:") && externalSubtitles.isNotEmpty()) {
        SubtitleRepository.clear()
        return
    }
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() } ?: return
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return
    SubtitleRepository.fetchAddonSubtitles(type, videoId)
}

internal fun PlayerScreenRuntime.setSubtitleDelay(delayMs: Int) {
    val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    subtitleDelayMs = clamped
    PlayerTrackPreferenceStorage.saveSubtitleDelayMs(playbackSession.videoId, clamped)
    playerController?.setSubtitleDelayMs(clamped)
}

internal fun PlayerScreenRuntime.loadSubtitleAutoSyncCues(force: Boolean = false) {
    val subtitle = selectedAddonSubtitle ?: return
    if (!force && subtitleAutoSyncState.cues.isNotEmpty()) return
    subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = true, errorMessage = null)
    scope.launch {
        val result = runCatching {
            val body = httpGetTextWithHeaders(
                url = subtitle.url,
                headers = sanitizePlaybackHeaders(activeSourceHeaders),
            )
            PlayerSubtitleCueParser.parse(body, subtitle.url)
        }
        result.fold(
            onSuccess = { cues ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    cues = cues,
                    isLoading = false,
                    errorMessage = if (cues.isEmpty()) localizedNoSubtitleLinesFound() else null,
                )
            },
            onFailure = { error ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = false,
                    errorMessage = error.message ?: localizedSubtitleLinesLoadError(),
                )
            },
        )
    }
}

internal fun PlayerScreenRuntime.captureSubtitleAutoSyncTime() {
    subtitleAutoSyncState = subtitleAutoSyncState.copy(
        capturedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
        errorMessage = null,
    )
    loadSubtitleAutoSyncCues()
}

internal fun PlayerScreenRuntime.applySubtitleAutoSyncCue(cue: SubtitleSyncCue) {
    val capturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: return
    val newDelayMs = (capturedPositionMs - cue.startTimeMs - SUBTITLE_AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    setSubtitleDelay(newDelayMs)
}

/**
 * Automatic variant of the manual Auto Sync: aligns the active add-on subtitle against the other
 * add-on subtitles of the same item and applies the confident constant-delay offset through the
 * player's subtitle delay. Only runs with a clean (zero) delay so an intentional manual offset is
 * never overwritten, and reports the outcome through [subtitleAutoSyncState].
 */
internal suspend fun PlayerScreenRuntime.runAutomaticSubtitleSync() {
    if (subtitleDelayMs != 0) return
    val subtitle = selectedAddonSubtitle ?: return
    val references = autoSyncReferenceCandidates(subtitle)
    if (references.isEmpty()) return

    autoSyncLog.d {
        "auto-sync start target=${subtitle.display} refs=${references.size} " +
            "langs=${references.joinToString { it.language ?: "?" }}"
    }
    subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = true, autoSyncNote = null, errorMessage = null)
    try {
        val decision = runCatching {
            val targetCues = PlayerSubtitleCueParser.parse(
                text = httpGetTextWithHeaders(
                    url = subtitle.url,
                    headers = sanitizePlaybackHeaders(activeSourceHeaders),
                ),
                sourceUrl = subtitle.url,
            )
            val referenceCues = references.mapNotNull { candidate ->
                runCatching {
                    AutomaticSubtitleSync.ReferenceCues(
                        key = candidate.selectionKey,
                        cues = PlayerSubtitleCueParser.parse(
                            text = httpGetTextWithHeaders(
                                url = candidate.url,
                                headers = sanitizePlaybackHeaders(activeSourceHeaders),
                            ),
                            sourceUrl = candidate.url,
                        ),
                    )
                }.onFailure { autoSyncLog.d { "reference fetch failed: ${candidate.url} (${it.message})" } }
                    .getOrNull()
            }
            autoSyncLog.d { "target cues=${targetCues.size} reference cues=${referenceCues.map { it.cues.size }}" }
            AutomaticSubtitleSync.computeAlignment(targetCues, referenceCues)
        }.onFailure { autoSyncLog.d { "auto-sync failed: ${it.message}" } }.getOrNull()

        when (decision) {
            is AutomaticSubtitleSync.Decision.ApplyOffset -> {
                if (subtitleDelayMs != 0) return
                val clamped = decision.offsetMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
                autoSyncLog.d { "auto-sync applied offset=${clamped}ms" }
                setSubtitleDelay(clamped)
                subtitleAutoSyncState = subtitleAutoSyncState.copy(autoSyncNote = localizedAutoSyncApplied(clamped))
            }
            is AutomaticSubtitleSync.Decision.NoConfidentMatch -> {
                autoSyncLog.d { "auto-sync no confident match" }
                subtitleAutoSyncState = subtitleAutoSyncState.copy(autoSyncNote = localizedAutoSyncNoMatch())
            }
            AutomaticSubtitleSync.Decision.NotApplicable, null -> {
                autoSyncLog.d { "auto-sync not applicable" }
                subtitleAutoSyncState = subtitleAutoSyncState.copy(autoSyncNote = null)
            }
        }
    } finally {
        subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = false)
    }
}

internal fun PlayerScreenRuntime.autoSyncReferenceCandidates(
    subtitle: AddonSubtitle,
): List<AddonSubtitle> = addonSubtitles
    .filter { it.selectionKey != subtitle.selectionKey }
    .sortedBy { if (it.language == subtitle.language) 0 else 1 }
    .take(8)
