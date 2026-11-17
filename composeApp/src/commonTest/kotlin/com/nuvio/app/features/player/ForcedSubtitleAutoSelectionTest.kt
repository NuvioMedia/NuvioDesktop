package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

class ForcedSubtitleAutoSelectionTest {
    @Test
    fun wrongLanguageForcedTrackIsTurnedOffWhileAddonSubtitlesLoad() {
        // The player auto-selected a Russian forced track (container default flag).
        val controller = RecordingController(
            subtitles = listOf(
                subtitleTrack(index = 0, language = "eng", isForced = false),
                subtitleTrack(index = 1, language = "rus", isForced = true, isSelected = true),
            ),
        )
        val runtime = runtime(controller).apply { isLoadingAddonSubtitles = true }

        runtime.refreshTracks()
        runtime.refreshTracks()

        assertEquals(listOf(-1), controller.subtitleSelections)
        assertEquals(-1, runtime.selectedSubtitleIndex)
    }

    @Test
    fun matchingForcedTrackIsKeptWhileAddonSubtitlesLoad() {
        val controller = RecordingController(
            subtitles = listOf(
                subtitleTrack(index = 0, language = "eng", isForced = false),
                subtitleTrack(index = 1, language = "eng", isForced = true, isSelected = true),
            ),
        )
        val runtime = runtime(controller).apply { isLoadingAddonSubtitles = true }

        runtime.refreshTracks()
        runtime.refreshTracks()

        assertEquals(1, runtime.selectedSubtitleIndex)
    }

    private fun runtime(controller: RecordingController) =
        PlayerScreenRuntime(
            PlayerScreenArgs(
                profileId = 1,
                title = "Test movie",
                sourceUrl = "https://example.com/video.mkv",
                sourceAudioUrl = null,
                sourceHeaders = emptyMap(),
                sourceResponseHeaders = emptyMap(),
                streamType = null,
                providerName = "Test provider",
                streamTitle = "Test stream",
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
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null,
                episodeThumbnail = null,
                contentType = "movie",
                videoId = "tt-forced-subtitle-test",
                parentMetaId = "tt-forced-subtitle-test",
                parentMetaType = "movie",
                providerAddonId = null,
                torrentInfoHash = null,
                torrentFileIdx = null,
                torrentFilename = null,
                torrentTrackers = emptyList(),
                initialPositionMs = 0L,
                initialProgressFraction = null,
                contentLanguage = null,
            ),
        ).apply {
            playerController = controller
            playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true)
            playerSettingsUiState = PlayerSettingsUiState(
                preferredAudioLanguage = "en",
                preferredSubtitleLanguage = "en",
                subtitleStyle = SubtitleStyleState.DEFAULT.copy(useForcedSubtitles = true),
            )
            trackPreferenceRestoreApplied = true
        }

    private fun subtitleTrack(
        index: Int,
        language: String,
        isForced: Boolean,
        isSelected: Boolean = false,
    ) = SubtitleTrack(
        index = index,
        id = "track-$index",
        label = "Track $index",
        language = language,
        isSelected = isSelected,
        isForced = isForced,
    )

    private class RecordingController(var subtitles: List<SubtitleTrack>) : PlayerEngineController {
        val subtitleSelections = mutableListOf<Int>()

        override fun getAudioTracks() = listOf(AudioTrack(0, "1", "English", "eng", true))
        override fun getSubtitleTracks() = subtitles
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) {
            subtitleSelections += index
            subtitles = subtitles.map { it.copy(isSelected = it.index == index) }
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }
}
