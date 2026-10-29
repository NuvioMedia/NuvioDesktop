package com.nuvio.app.features.autosync

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.streams.StreamSubtitle
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class DesktopAutoSyncCoordinator(
    private val scope: CoroutineScope,
    private val sourceUrl: String,
    private val sourceHeaders: Map<String, String>,
    private val getExternalSubtitles: () -> List<StreamSubtitle>,
    private val getPreferredLanguage: () -> String?,
) {
    private val log = Logger.withTag("AutoSync")
    private var job: Job? = null
    private var operationToken = 0L
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null
    private var failureListener: ((AutoSyncFailure) -> Unit)? = null

    fun wrap(controller: PlayerEngineController): PlayerEngineController =
        AutoSyncDesktopEngineController(controller, this)

    fun setCandidates(value: List<AutoSyncSubtitleCandidate>) {
        candidates = value.distinctBy { it.url }
    }

    fun setAppliedListener(listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?) {
        appliedListener = listener
    }

    fun setFailureListener(listener: ((AutoSyncFailure) -> Unit)?) {
        failureListener = listener
    }

    fun cancel() {
        operationToken++
        job?.cancel()
        job = null
    }

    fun dispose() {
        cancel()
        appliedListener = null
        failureListener = null
    }

    fun start(
        url: String,
        candidateScope: AutoSyncCandidateScope,
        base: PlayerEngineController,
    ) {
        cancel()
        val token = operationToken
        base.setSubtitleUri(url)
        AutoSyncPreferencesRepository.ensureLoaded()
        if (decideAutoSyncStart(AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value) ==
            AutoSyncStartAction.ATTACH_ORIGINAL ||
            !(sourceUrl.startsWith("https://", true) || sourceUrl.startsWith("http://", true))
        ) {
            return
        }

        val subtitleHeaders = getExternalSubtitles().firstOrNull { it.url == url }?.headers.orEmpty()
        val subtitleLanguage = candidates.firstOrNull { it.url == url }?.language
            ?.takeIf { it.isNotBlank() } ?: getPreferredLanguage()
        EmbeddedSubtitleTimelineLoader.prefetch(scope, sourceUrl, sourceHeaders)

        job = scope.launch {
            try {
                var outcome: AutoSyncAnalysisOutcome? = null
                val resolved = AutomaticSubtitleSync.findTimelineRetime(
                    sourceKey = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    selectedSubtitleUrl = url,
                    selectedSubtitleHeaders = subtitleHeaders,
                    preferredLanguage = subtitleLanguage,
                    alternativeSubtitles = candidateScope.alternativeCandidates(candidates),
                    alternativeSubtitlesProvider = if (candidateScope.usesAlternativeProvider) {
                        { candidates }
                    } else null,
                    onAnalysisOutcome = { outcome = it },
                    allowLiveReference = false,
                )
                currentCoroutineContext().ensureActive()
                if (token != operationToken) return@launch
                if (resolved == null) {
                    log.i { "no usable reference: $outcome" }
                    failureListener?.invoke(
                        when (outcome) {
                            AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS,
                            AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE -> AutoSyncFailure.NO_REFERENCE
                            else -> AutoSyncFailure.FAILED
                        },
                    )
                    return@launch
                }

                val chosenUrl = resolved.subtitleUrl
                val tolerance = AutoSyncPreferencesRepository.syncToleranceMs.value
                val withinTolerance = tolerance > 0 && chosenUrl == url &&
                    resolved.timeline.maxAlignmentShiftMs() <= tolerance
                if (!withinTolerance) {
                    val path = writeRetimedSubtitle(chosenUrl, resolved, subtitleLanguage, subtitleHeaders)
                    currentCoroutineContext().ensureActive()
                    if (token != operationToken) return@launch
                    if (path == null) {
                        log.w { "subtitle format cannot be retimed" }
                        failureListener?.invoke(AutoSyncFailure.FAILED)
                        return@launch
                    }
                    base.setSubtitleUri(path)
                }
                base.setSubtitleDelayMs(0)
                appliedListener?.invoke(chosenUrl, 0)
                AutoSyncSyncedSubtitle.mark(chosenUrl)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                log.w(error) { "autosync failed" }
                if (token == operationToken) failureListener?.invoke(AutoSyncFailure.FAILED)
            }
        }
    }

    private suspend fun writeRetimedSubtitle(
        subtitleUrl: String,
        resolved: AutoSyncResolvedTimeline,
        languageHint: String?,
        fallbackHeaders: Map<String, String>,
    ): String? {
        val body = resolved.subtitleBody ?: AutomaticSubtitleSync.downloadSubtitleBody(
            url = subtitleUrl,
            headers = resolved.subtitleHeaders.ifEmpty { fallbackHeaders },
            languageHint = languageHint,
        )
        val retimed = withContext(autoSyncCpuDispatcher) {
            AutoSyncSubtitleTextRetimer.retime(body, resolved.timeline)
        } ?: return null
        return withContext(Dispatchers.IO) {
            val cacheDir = DesktopStorage.cacheDir.resolve("subtitles").toFile().also { it.mkdirs() }
            val prefix = "autosync_${Integer.toHexString(subtitleUrl.hashCode())}_"
            val extension = when {
                retimed.trimStart().startsWith("WEBVTT") -> "vtt"
                retimed.contains("[Events]", ignoreCase = true) -> "ass"
                else -> "srt"
            }
            val file = File(cacheDir, "$prefix${Integer.toHexString(retimed.hashCode())}.$extension")
            cacheDir.listFiles { candidate -> candidate.name.startsWith(prefix) && candidate != file }
                ?.forEach { it.delete() }
            file.writeText(retimed, Charsets.UTF_8)
            file.absolutePath
        }
    }
}

private class AutoSyncDesktopEngineController(
    private val base: PlayerEngineController,
    private val coordinator: DesktopAutoSyncCoordinator,
) : PlayerEngineController by base, AutoSyncPlayerController {

    override fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>) =
        coordinator.setCandidates(candidates)

    override fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    ) = coordinator.setAppliedListener(listener)

    override fun setAutoSyncFailureListener(listener: ((AutoSyncFailure) -> Unit)?) =
        coordinator.setFailureListener(listener)

    override fun setSubtitleUri(url: String) {
        coordinator.cancel()
        base.setSubtitleUri(url)
    }

    override fun setSubtitleUriWithAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.STARTUP_SEARCH, base)

    override fun setSubtitleUriWithSelectedAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.SELECTED_ONLY, base)

    override fun selectSubtitleTrack(index: Int) {
        coordinator.cancel()
        base.selectSubtitleTrack(index)
    }

    override fun clearExternalSubtitle() {
        coordinator.cancel()
        base.clearExternalSubtitle()
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        coordinator.cancel()
        base.clearExternalSubtitleAndSelect(trackIndex)
    }

    override fun cancelForManualSubtitleDelay() {
        coordinator.cancel()
        AutoSyncSyncedSubtitle.clear()
    }
}
