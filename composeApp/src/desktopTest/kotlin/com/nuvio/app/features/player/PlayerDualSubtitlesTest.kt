package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.autosync.AutoSyncPlayerController
import com.nuvio.app.features.autosync.AutoSyncSubtitleCandidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerDualSubtitlesTest {
    @After
    fun cleanup() {
        PlayerTrackPreferenceStorage.save("tt-dual-test", PersistedPlayerTrackPreference())
    }

    @Test
    fun automaticSelectionsFillBothSlots() = runTest {
        val controller = RecordingController()
        val runtime = runtime(controller, backgroundScope)
        runtime.refreshTracks()
        runCurrent()
        assertEquals(0, runtime.selectedSubtitleIndex)
        assertEquals(1, (runtime.secondarySubtitleSelection as SubtitleSelectionOption.BuiltIn).track.index)
    }

    @Test
    fun dualModeKeepsPgsTracksInTheListButSelectsText() = runTest {
        val controller = RecordingController().apply {
            tracks = listOf(tracks[0].copy(codec = "hdmv_pgs_subtitle"),
                tracks[1], SubtitleTrack(2, "3", "English SRT", "en", codec = "subrip"))
        }
        val runtime = runtime(controller, backgroundScope)
        runtime.refreshTracks()
        runCurrent()
        assertEquals(2, runtime.selectedSubtitleIndex)
        assertTrue(runtime.subtitleTracks.any { it.id == "1" && it.isBitmap })
        val before = controller.selections.size
        for (slot in SubtitleSlot.entries) {
            runtime.selectDualSubtitle(slot, SubtitleSelectionOption.BuiltIn(controller.tracks[0]))
        }
        runCurrent()
        assertEquals(before, controller.selections.size)
        assertEquals(2, runtime.selectedSubtitleIndex)
    }

    @Test
    fun enablingDualModeDoesNotRetainAnExplicitBitmapPrimary() = runTest {
        val controller = RecordingController().apply {
            tracks = listOf(tracks[0].copy(codec = "hdmv_pgs_subtitle", isSelected = true),
                tracks[1], SubtitleTrack(2, "3", "English SRT", "en", codec = "subrip"))
        }
        val runtime = runtime(controller, backgroundScope).apply {
            selectedSubtitleIndex = 0
            isUserExplicitSubtitleSelection = true
        }
        runtime.refreshTracks()
        runCurrent()
        assertEquals(2, runtime.selectedSubtitleIndex)
        assertFalse(runtime.isUserExplicitSubtitleSelection)
    }

    @Test
    fun restoringSecondaryUsesNativeTrackIndexInsteadOfListPosition() = runTest {
        val controller = RecordingController().apply {
            tracks = listOf(
                tracks[0],
                tracks[1].copy(index = 2),
                SubtitleTrack(3, "4", "Russian", "ru", codec = "subrip"),
            )
        }
        val runtime = runtime(controller, backgroundScope)
        runtime.persistSecondarySubtitlePreference(SubtitleSelectionOption.BuiltIn(controller.tracks[1]))
        runtime.secondarySubtitleRestoreApplied = false
        runtime.refreshTracks()
        runCurrent()
        val secondary = (runtime.secondarySubtitleSelection as SubtitleSelectionOption.BuiltIn).track
        assertEquals("fr", secondary.language)
        assertEquals("2", secondary.id)
    }

    @Test
    fun savedRussianSecondaryCannotOverrideEnglishAndSimplifiedChinesePreferences() = runTest {
        val controller = RecordingController().apply {
            tracks = listOf(tracks[0], SubtitleTrack(1, "2", "Russian", "ru", codec = "subrip"))
        }
        val runtime = runtime(controller, backgroundScope).apply {
            playerSettingsUiState = playerSettingsUiState.copy(secondaryPreferredSubtitleLanguage = "zh-cn")
        }
        runtime.persistSecondarySubtitlePreference(SubtitleSelectionOption.BuiltIn(controller.tracks[1]))
        runtime.secondarySubtitleRestoreApplied = false
        runtime.refreshTracks()
        runCurrent()
        assertEquals(0, runtime.selectedSubtitleIndex)
        assertNull(runtime.secondarySubtitleSelection)
        assertFalse(runtime.isUserExplicitSecondarySubtitleSelection)
    }

    @Test
    fun secondaryCannotChangeFromChineseToRussianWhenNativeIdIsReused() = runTest {
        val controller = RecordingController().apply {
            tracks = listOf(tracks[0], SubtitleTrack(1, "2", "Chinese Simplified", "zh-cn", codec = "subrip"))
        }
        val runtime = runtime(controller, backgroundScope).apply {
            playerSettingsUiState = playerSettingsUiState.copy(secondaryPreferredSubtitleLanguage = "zh-cn")
        }
        runtime.refreshTracks()
        runCurrent()
        runtime.selectDualSubtitle(SubtitleSlot.Secondary, SubtitleSelectionOption.BuiltIn(controller.tracks[1]))
        runCurrent()
        controller.tracks = listOf(controller.tracks[0], controller.tracks[1].copy(label = "Russian", language = "ru"))
        runtime.refreshTracks()
        runCurrent()
        assertEquals(0, runtime.selectedSubtitleIndex)
        assertNull(runtime.secondarySubtitleSelection)
        assertFalse(runtime.isUserExplicitSecondarySubtitleSelection)
    }

    @Test
    fun reusedRussianTrackIsClearedBeforeChineseAddonFinishesLoading() = runTest {
        val controller = RecordingController().apply {
            tracks = listOf(tracks[0], SubtitleTrack(1, "2", "Chinese Simplified", "zh-cn", codec = "subrip"))
        }
        val runtime = runtime(controller, backgroundScope).apply {
            playerSettingsUiState = playerSettingsUiState.copy(secondaryPreferredSubtitleLanguage = "zh-cn")
        }
        runtime.refreshTracks()
        runCurrent()
        runtime.selectDualSubtitle(SubtitleSlot.Secondary, SubtitleSelectionOption.BuiltIn(controller.tracks[1]))
        runCurrent()
        controller.tracks = listOf(controller.tracks[0], controller.tracks[1].copy(label = "Russian", language = "ru"))
        val chinese = AddonSubtitle("zh-addon", "https://example.com/zh.srt", "zh-cn", "Chinese Simplified")
        runtime.addonSubtitles = listOf(chinese)
        val gate = CompletableDeferred<Unit>()
        controller.externalGate = gate
        runtime.refreshTracks()
        assertNull(runtime.secondarySubtitleSelection)
        runCurrent()
        assertTrue(runtime.secondarySubtitleLoading)
        assertNull(controller.selections.last { it.first == SubtitleSlot.Secondary }.second)
        assertNull(runtime.secondarySubtitleSelection)
        gate.complete(Unit)
        runCurrent()
        assertEquals(chinese.url, (runtime.secondarySubtitleSelection as SubtitleSelectionOption.Addon).subtitle.url)
        assertFalse(runtime.secondarySubtitleNeedsClear)
    }

    @Test
    fun englishAndRussianFileWaitsForChineseAddonWithoutSelectingRussian() = runTest {
        for (preferBuiltIn in listOf(false, true)) {
            val controller = RecordingController().apply {
                tracks = listOf(tracks[0], SubtitleTrack(1, "2", "Russian", "ru", codec = "subrip"))
                audio = listOf(AudioTrack(0, "a", "English", "en", true), AudioTrack(1, "b", "Russian", "ru"))
            }
            val runtime = runtime(controller, backgroundScope).apply {
                playerSettingsUiState = playerSettingsUiState.copy(
                    secondaryPreferredSubtitleLanguage = "zh-cn",
                    subtitleStyle = subtitleStyle.copy(preferBuiltInSubtitles = preferBuiltIn),
                )
                isLoadingAddonSubtitles = true
            }
            runtime.refreshTracks()
            runCurrent()
            assertEquals(0, runtime.selectedSubtitleIndex)
            assertNull(runtime.secondarySubtitleSelection)
            val chinese = AddonSubtitle("zh-addon", "https://example.com/zh.srt", "zh-cn", "Chinese Simplified")
            runtime.addonSubtitles = listOf(
                AddonSubtitle("ru-addon", "https://example.com/ru.srt", "ru", "Russian"), chinese,
            )
            runtime.isLoadingAddonSubtitles = false
            runtime.refreshTracks()
            runCurrent()
            assertEquals(0, runtime.selectedSubtitleIndex)
            assertEquals(chinese.url, (runtime.secondarySubtitleSelection as SubtitleSelectionOption.Addon).subtitle.url)
            assertTrue(controller.selections.none { it.first == SubtitleSlot.Secondary &&
                (it.second as? PlayerSubtitleSelection.BuiltIn)?.trackId == "2" })
        }
    }

    @Test
    fun savedChineseAddonStillRestoresWithSimplifiedChinesePreference() = runTest {
        val controller = RecordingController()
        val runtime = runtime(controller, backgroundScope).apply {
            playerSettingsUiState = playerSettingsUiState.copy(secondaryPreferredSubtitleLanguage = "zh-cn")
        }
        val chinese = AddonSubtitle("zh-addon", "https://example.com/zh.srt", "zh", "Chinese")
        runtime.addonSubtitles = listOf(chinese)
        runtime.persistSecondarySubtitlePreference(SubtitleSelectionOption.Addon(chinese))
        runtime.secondarySubtitleRestoreApplied = false
        runtime.refreshTracks()
        runCurrent()
        assertEquals(0, runtime.selectedSubtitleIndex)
        assertEquals(chinese.url, (runtime.secondarySubtitleSelection as SubtitleSelectionOption.Addon).subtitle.url)
    }

    @Test
    fun savedPrimaryOffDoesNotSuppressConfiguredEnglishOnNewDualPlayback() = runTest {
        val controller = RecordingController()
        val runtime = runtime(controller, backgroundScope).apply {
            playerSettingsUiState = playerSettingsUiState.copy(secondaryPreferredSubtitleLanguage = "zh-cn")
        }
        val chinese = AddonSubtitle("zh-addon", "https://example.com/zh.srt", "zh", "Chinese")
        runtime.addonSubtitles = listOf(chinese)
        runtime.persistInternalSubtitlePreference(null)
        runtime.persistSecondarySubtitlePreference(SubtitleSelectionOption.Addon(chinese))
        runtime.trackPreferenceRestoreApplied = false
        runtime.secondarySubtitleRestoreApplied = false
        runtime.refreshTracks()
        runCurrent()
        assertEquals(0, runtime.selectedSubtitleIndex)
        assertEquals(chinese.url, (runtime.secondarySubtitleSelection as SubtitleSelectionOption.Addon).subtitle.url)
        assertFalse(runtime.isUserExplicitSubtitleSelection)
    }

    @Test
    fun savedSecondaryOffDoesNotSuppressConfiguredChineseOnNewDualPlayback() = runTest {
        val controller = RecordingController().apply {
            tracks = listOf(tracks[0], SubtitleTrack(1, "2", "Chinese Simplified", "zh-cn", codec = "subrip"))
        }
        val runtime = runtime(controller, backgroundScope).apply {
            playerSettingsUiState = playerSettingsUiState.copy(secondaryPreferredSubtitleLanguage = "zh-cn")
        }
        runtime.persistSecondarySubtitlePreference(null)
        runtime.secondarySubtitleRestoreApplied = false
        runtime.refreshTracks()
        runCurrent()
        assertEquals(0, runtime.selectedSubtitleIndex)
        assertEquals("zh-cn", (runtime.secondarySubtitleSelection as SubtitleSelectionOption.BuiltIn).track.language)
        assertFalse(runtime.isUserExplicitSecondarySubtitleSelection)
    }

    @Test
    fun manuallyTurningEitherSlotOffStillAppliesForCurrentPlayback() = runTest {
        val controller = RecordingController()
        val runtime = runtime(controller, backgroundScope)
        runtime.refreshTracks()
        runCurrent()
        runtime.selectDualSubtitle(SubtitleSlot.Primary, null)
        runtime.selectDualSubtitle(SubtitleSlot.Secondary, null)
        runCurrent()
        runtime.refreshTracks()
        runCurrent()
        assertEquals(-1, runtime.selectedSubtitleIndex)
        assertNull(runtime.secondarySubtitleSelection)
        assertTrue(runtime.isUserExplicitSubtitleSelection)
        assertTrue(runtime.isUserExplicitSecondarySubtitleSelection)
    }

    @Test
    fun firstFoundAddonTracksStayInBothSlotsWhenBuiltInPreferenceIsOff() = runTest {
        val controller = RecordingController()
        val builtIn = controller.tracks
        controller.tracks = emptyList()
        val runtime = runtime(controller, backgroundScope)
        val english = AddonSubtitle("en-addon", "https://example.com/en.srt", "en", "English")
        val french = AddonSubtitle("fr-addon", "https://example.com/fr.srt", "fr", "French")
        runtime.addonSubtitles = listOf(english, french)
        runtime.refreshTracks()
        runCurrent()
        controller.tracks = builtIn
        runtime.refreshTracks()
        runCurrent()
        assertEquals(english.url, runtime.selectedAddonSubtitle?.url)
        assertEquals(french.url, (runtime.secondarySubtitleSelection as SubtitleSelectionOption.Addon).subtitle.url)
    }

    @Test
    fun builtInPreferenceUpgradesBothSlotsWhenEmbeddedTracksArrive() = runTest {
        val controller = RecordingController()
        val builtIn = controller.tracks
        controller.tracks = emptyList()
        val runtime = runtime(controller, backgroundScope).apply {
            playerSettingsUiState = playerSettingsUiState.copy(
                subtitleStyle = subtitleStyle.copy(preferBuiltInSubtitles = true),
            )
            addonSubtitles = listOf(
                AddonSubtitle("en-addon", "https://example.com/en.srt", "en", "English"),
                AddonSubtitle("fr-addon", "https://example.com/fr.srt", "fr", "French"),
            )
        }
        runtime.refreshTracks()
        runCurrent()
        assertTrue(runtime.selectedAddonSubtitle != null)
        assertTrue(runtime.secondarySubtitleSelection is SubtitleSelectionOption.Addon)
        controller.tracks = builtIn
        runtime.refreshTracks()
        runCurrent()
        assertEquals(0, runtime.selectedSubtitleIndex)
        assertEquals(1, (runtime.secondarySubtitleSelection as SubtitleSelectionOption.BuiltIn).track.index)
    }

    @Test
    fun builtInPreferenceDoesNotAllowNormalAddonToReplaceMissingForcedSubtitle() = runTest {
        val controller = RecordingController()
        val runtime = runtime(controller, backgroundScope).apply {
            playerSettingsUiState = playerSettingsUiState.copy(
                subtitleStyle = subtitleStyle.copy(useForcedSubtitles = true, preferBuiltInSubtitles = true),
            )
            addonSubtitles = listOf(AddonSubtitle("en-addon", "https://example.com/en.srt", "en", "English"))
        }
        runtime.refreshTracks()
        runCurrent()
        assertEquals(1, runtime.selectedSubtitleIndex)
        assertNull(runtime.secondarySubtitleSelection)
        assertTrue(controller.selections.none { it.second is PlayerSubtitleSelection.External })
    }

    @Test
    fun secondaryOffIsAnExplicitChoiceThatSurvivesRefresh() = runTest {
        val controller = RecordingController()
        val runtime = runtime(controller, backgroundScope)
        runtime.refreshTracks()
        runCurrent()
        runtime.handlePlayerControlsEvent("subtitleSlot", 1.0)
        runtime.handlePlayerControlsEvent("selectBuiltInSubtitleTrack", -1.0)
        runCurrent()
        runtime.refreshTracks()
        runCurrent()
        assertEquals(0, runtime.selectedSubtitleIndex)
        assertNull(runtime.secondarySubtitleSelection)
        assertTrue(runtime.isUserExplicitSecondarySubtitleSelection)
    }

    @Test
    fun changingPrimaryDoesNotCancelPendingSecondaryLoad() = runTest {
        val controller = RecordingController()
        val runtime = runtime(controller, backgroundScope)
        runtime.refreshTracks()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        controller.secondaryGate = gate
        runtime.selectDualSubtitle(SubtitleSlot.Secondary, SubtitleSelectionOption.BuiltIn(controller.tracks[2]))
        runCurrent()
        assertTrue(runtime.secondarySubtitleLoading)
        runtime.selectDualSubtitle(SubtitleSlot.Primary, null)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(-1, runtime.selectedSubtitleIndex)
        assertEquals(2, (runtime.secondarySubtitleSelection as SubtitleSelectionOption.BuiltIn).track.index)
    }

    @Test
    fun sourceResetCancelsPendingSelectionWithoutApplyingItToNextVideo() = runTest {
        val controller = RecordingController()
        val runtime = runtime(controller, backgroundScope)
        runtime.refreshTracks()
        runCurrent()
        val before = controller.selections.size
        val gate = CompletableDeferred<Unit>()
        controller.secondaryGate = gate
        runtime.selectDualSubtitle(SubtitleSlot.Secondary, SubtitleSelectionOption.BuiltIn(controller.tracks[2]))
        runCurrent()
        runtime.resetDualSubtitleState()
        gate.complete(Unit)
        runCurrent()
        assertNull(runtime.secondarySubtitleSelection)
        assertFalse(runtime.secondarySubtitleLoading)
        assertEquals(before, controller.selections.size)
    }

    @Test
    fun manualSecondaryChoiceDoesNotOverwriteSavedPrimaryChoice() = runTest {
        val controller = RecordingController()
        val runtime = runtime(controller, backgroundScope)
        runtime.refreshTracks()
        runCurrent()
        runtime.selectDualSubtitle(SubtitleSlot.Primary, SubtitleSelectionOption.BuiltIn(controller.tracks[0]))
        runCurrent()
        runtime.selectDualSubtitle(SubtitleSlot.Secondary, SubtitleSelectionOption.BuiltIn(controller.tracks[2]))
        runCurrent()
        val preference = PlayerTrackPreferenceStorage.load("tt-dual-test")!!
        assertEquals("1", preference.subtitleTrackId)
        assertTrue(preference.secondarySubtitlePreference!!.contains("German"))
    }

    @Test
    fun manualChoicesSurviveLatePreferredAddonResults() = runTest {
        val controller = RecordingController()
        val runtime = runtime(controller, backgroundScope)
        runtime.refreshTracks()
        runCurrent()
        runtime.selectDualSubtitle(SubtitleSlot.Secondary, SubtitleSelectionOption.BuiltIn(controller.tracks[2]))
        runCurrent()
        runtime.addonSubtitles = listOf(AddonSubtitle("fr-addon", "https://example.com/fr.srt", "fr", "French"))
        runtime.refreshTracks()
        runCurrent()
        assertEquals(2, (runtime.secondarySubtitleSelection as SubtitleSelectionOption.BuiltIn).track.index)
        assertEquals(0, runtime.selectedSubtitleIndex)
    }

    @Test
    fun failedPreferredAddonPromotesWorkingSecondaryWithoutRetryLoop() = runTest {
        val controller = RecordingController().apply {
            tracks = tracks.filter { it.language == "fr" }
            rejectExternal = true
        }
        val runtime = runtime(controller, backgroundScope)
        runtime.addonSubtitles = listOf(AddonSubtitle("en-addon", "https://example.com/en.srt", "en", "English"))
        runtime.refreshTracks()
        runCurrent()
        assertEquals(1, runtime.selectedSubtitleIndex)
        assertNull(runtime.secondarySubtitleSelection)
        assertEquals(1, controller.selections.count { it.second is PlayerSubtitleSelection.External })
    }

    @Test
    fun restoredPrimaryAddonExistsBeforeCatalogLoads() = runTest {
        val controller = RecordingController()
        val runtime = runtime(controller, backgroundScope)
        PlayerTrackPreferenceStorage.save("tt-dual-test", PersistedPlayerTrackPreference(
            subtitleType = PersistedSubtitleSelectionType.ADDON,
            subtitleLanguage = "en", subtitleName = "Saved English",
            addonSubtitleId = "saved-en", addonSubtitleUrl = "https://example.com/saved-en.srt",
            addonSubtitleItemId = runtime.subtitlePreferenceItemId,
        ))
        runtime.trackPreferenceRestoreApplied = false
        runtime.refreshTracks()
        runCurrent()
        assertEquals("https://example.com/saved-en.srt", runtime.selectedAddonSubtitle?.url)
        assertTrue(runtime.visibleAddonSubtitles.any { it.url == "https://example.com/saved-en.srt" })
    }

    @Test
    fun automaticSecondaryKeepsAutoSyncsAlternativeAfterTrackRefresh() = runTest {
        val controller = RecordingController().apply { tracks = tracks.filter { it.language != "fr" } }
        val runtime = runtime(controller, backgroundScope)
        val original = AddonSubtitle("fr-original", "https://example.com/fr-original.srt", "fr", "French original")
        val alternate = AddonSubtitle("fr-alternate", "https://example.com/fr-alternate.srt", "fr", "French alternate")
        runtime.addonSubtitles = listOf(original, alternate)
        runtime.configureAutoSyncController(controller)
        runtime.refreshTracks()
        runCurrent()
        controller.slotListener!!(SubtitleSlot.Secondary, alternate.url, 0)
        runtime.refreshTracks()
        runCurrent()
        assertEquals(alternate.url, (runtime.secondarySubtitleSelection as SubtitleSelectionOption.Addon).subtitle.url)
        assertEquals(0, runtime.selectedSubtitleIndex)
    }

    @Test
    fun builtInPreferenceTakesPriorityOverAutomaticAddonAutoSync() = runTest {
        val controller = RecordingController()
        val builtIn = controller.tracks
        controller.tracks = builtIn.filter { it.language != "fr" }
        val runtime = runtime(controller, backgroundScope).apply {
            playerSettingsUiState = playerSettingsUiState.copy(
                subtitleStyle = subtitleStyle.copy(preferBuiltInSubtitles = true),
            )
            addonSubtitles = listOf(AddonSubtitle("fr-addon", "https://example.com/fr.srt", "fr", "French"))
        }
        runtime.configureAutoSyncController(controller)
        runtime.refreshTracks()
        runCurrent()
        controller.slotListener!!(SubtitleSlot.Secondary, runtime.addonSubtitles.single().url, 0)
        controller.tracks = builtIn
        runtime.refreshTracks()
        runCurrent()
        assertEquals(1, (runtime.secondarySubtitleSelection as SubtitleSelectionOption.BuiltIn).track.index)
    }

    @Test
    fun secondarySelectionFollowsItsTrackIdWhenIndexesChange() = runTest {
        val controller = RecordingController()
        val runtime = runtime(controller, backgroundScope)
        runtime.refreshTracks()
        runCurrent()
        runtime.selectDualSubtitle(SubtitleSlot.Secondary, SubtitleSelectionOption.BuiltIn(controller.tracks[2]))
        runCurrent()
        controller.tracks = controller.tracks.map { it.copy(index = it.index + 10) }
        runtime.refreshTracks()
        runCurrent()
        assertEquals("3", (runtime.secondarySubtitleSelection as SubtitleSelectionOption.BuiltIn).track.id)
        assertEquals(12, (runtime.secondarySubtitleSelection as SubtitleSelectionOption.BuiltIn).track.index)
    }

    private fun runtime(controller: RecordingController, testScope: CoroutineScope) = PlayerScreenRuntime(
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
            parentMetaId = "tt-dual-test",
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
        scope = testScope
        playerSettingsUiState = PlayerSettingsUiState(preferredSubtitleLanguage = "en", secondaryPreferredSubtitleLanguage = "fr", dualSubtitlesEnabled = true)
        playerController = controller
        playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false)
        trackPreferenceRestoreApplied = true
        isUserExplicitAudioSelection = true
        preferredAudioSelectionApplied = true
        secondarySubtitleRestoreApplied = true
    }

    private class RecordingController : PlayerEngineController, AutoSyncPlayerController {
        var slotListener: ((SubtitleSlot, String, Int) -> Unit)? = null
        var rejectExternal = false
        override fun setSubtitleSlotAutoSyncAppliedListener(listener: ((SubtitleSlot, String, Int) -> Unit)?) { slotListener = listener }
        override fun cancelForManualSubtitleDelay() = Unit
        override fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>) = Unit
        override fun setSubtitleUriWithAutoSync(url: String) = Unit
        override fun setSubtitleUriWithSelectedAutoSync(url: String) = Unit
        override fun setAutoSyncAppliedListener(listener: ((String, Int) -> Unit)?) = Unit
        var tracks = listOf(
            SubtitleTrack(0, "1", "English", "en", codec = "subrip"),
            SubtitleTrack(1, "2", "French", "fr", codec = "subrip"),
            SubtitleTrack(2, "3", "German", "de", codec = "subrip"),
        )
        var secondaryGate: CompletableDeferred<Unit>? = null
        var externalGate: CompletableDeferred<Unit>? = null
        val selections = mutableListOf<Pair<SubtitleSlot, PlayerSubtitleSelection?>>()
        override suspend fun setSubtitleSelection(slot: SubtitleSlot, selection: PlayerSubtitleSelection?): Boolean {
            if (slot == SubtitleSlot.Secondary) secondaryGate?.await()
            if (selection is PlayerSubtitleSelection.External) externalGate?.await()
            selections += slot to selection
            if (rejectExternal && selection is PlayerSubtitleSelection.External) return false
            if (slot == SubtitleSlot.Primary) {
                val index = (selection as? PlayerSubtitleSelection.BuiltIn)?.index ?: -1
                tracks = tracks.map { it.copy(isSelected = it.index == index) }
            }
            return true
        }
        var audio = listOf(AudioTrack(0, "a", "English", "en", true))
        override fun getAudioTracks() = audio
        override fun getSubtitleTracks() = tracks
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
    }
}
