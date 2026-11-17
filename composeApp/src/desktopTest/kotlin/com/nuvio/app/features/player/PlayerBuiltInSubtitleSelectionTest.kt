package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerBuiltInSubtitleSelectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun cleanup() {
        PlayerTrackPreferenceStorage.save("tt2026", PersistedPlayerTrackPreference())
        scope.cancel()
    }

    @Test
    fun builtInSubtitlePickedFromPlayerControlsSurvivesTheNextTrackRefresh() {
        val controller = RecordingController(
            subtitleTracks = listOf(
                SubtitleTrack(index = 0, id = "0", label = "English", language = "en", isSelected = true),
                SubtitleTrack(index = 1, id = "1", label = "Spanish", language = "es"),
            ),
        )
        val runtime = runtime(controller)
        runtime.subtitleTracks = controller.getSubtitleTracks()
        runtime.addonSubtitles = listOf(
            AddonSubtitle(
                id = "episode-1",
                url = "https://example.com/episode-1.srt",
                language = "en",
                display = "English",
                addonName = "Test",
            ),
        )
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false)
        runtime.hasScannedTextTracksOnce = true
        runtime.trackPreferenceRestoreApplied = true
        runtime.preferredSubtitleSelectionApplied = true

        assertTrue(runtime.handlePlayerControlsEvent("selectBuiltInSubtitleTrack", 1.0))

        // Ending a buffering cycle refreshes tracks, which re-runs subtitle auto-selection.
        runtime.refreshTracks()

        assertEquals(1, runtime.selectedSubtitleIndex)
        assertTrue(runtime.isUserExplicitSubtitleSelection)
        assertEquals(listOf(1), controller.subtitleSelections)
        assertTrue(controller.subtitleUrls.isEmpty())
    }

    @Test
    fun addonSubtitlePickedFromPlayerControlsKeepsItsSelectionAndSurvivesTheNextRefresh() {
        val controller = RecordingController()
        val runtime = runtime(controller)
        val englishAddon = addonSubtitle("en")
        val frenchAddon = addonSubtitle("fr")
        runtime.addonSubtitles = listOf(englishAddon, frenchAddon)
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false)
        runtime.hasScannedTextTracksOnce = true
        runtime.trackPreferenceRestoreApplied = true
        runtime.preferredSubtitleSelectionApplied = true

        val frenchIndex = runtime.visibleAddonSubtitles.indexOfFirst { it.url == frenchAddon.url }
        assertTrue(frenchIndex >= 0)
        assertTrue(runtime.handlePlayerControlsEvent("selectAddonSubtitle", frenchIndex.toDouble()))

        runtime.refreshTracks()

        assertEquals(frenchAddon.selectionKey, runtime.selectedAddonSubtitleId)
        assertEquals(frenchAddon.url, runtime.selectedAddonSubtitle?.url)
        assertTrue(runtime.isUserExplicitSubtitleSelection)
        assertEquals(listOf(frenchAddon.url), controller.subtitleUrls)
    }

    private fun addonSubtitle(language: String) = AddonSubtitle(
        id = "addon-$language",
        url = "https://example.com/episode-1-$language.srt",
        language = language,
        display = language,
        addonName = "Test",
    )

    private fun runtime(controller: RecordingController) = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Series",
            sourceUrl = "https://example.com/episode-1.mp4",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Test",
            streamTitle = "Episode 1",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = {},
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 1,
            episodeTitle = "Episode 1",
            episodeThumbnail = null,
            contentType = "series",
            videoId = "tt2026:1:1",
            parentMetaId = "tt2026",
            parentMetaType = "series",
            providerAddonId = "test",
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    ).apply {
        scope = this@PlayerBuiltInSubtitleSelectionTest.scope
        playerSettingsUiState = PlayerSettingsUiState(preferredSubtitleLanguage = "en")
        playerController = controller
    }

    private class RecordingController(
        subtitleTracks: List<SubtitleTrack> = emptyList(),
    ) : PlayerEngineController {
        val subtitleUrls = mutableListOf<String>()
        val subtitleSelections = mutableListOf<Int>()
        private var tracks = subtitleTracks

        override fun setSubtitleUri(url: String) {
            subtitleUrls += url
        }

        override fun selectSubtitleTrack(index: Int) {
            subtitleSelections += index
            tracks = tracks.map { track -> track.copy(isSelected = track.index == index) }
        }

        override fun getAudioTracks() = emptyList<AudioTrack>()
        override fun getSubtitleTracks() = tracks
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }
}
