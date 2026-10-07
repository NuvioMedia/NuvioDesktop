package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerSubtitleSelectionRuntimeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val englishEpisodeOne = subtitle(episode = 1, language = "en", addonName = "Subs")
    private val frenchEpisodeOne = subtitle(episode = 1, language = "fr", addonName = "Subs")

    @Before
    fun requireIsolatedStorage() {
        // These tests write player preferences through DesktopStorage; never let them touch real user data.
        assumeTrue("DesktopStorage is not isolated in a temporary directory", storageIsIsolated())
        DesktopStorage.wipe()
    }

    @After
    fun cleanup() {
        scope.cancel()
        if (storageIsIsolated()) DesktopStorage.wipe()
    }

    @Test
    fun desktopControlsAddonPickIsNotReplacedByAutoSelection() {
        val runtime = runtime(bingeGroup = null)
        val controller = runtime.playerController as RecordingController
        runtime.refreshTracks()
        assertEquals(listOf(englishEpisodeOne.url), controller.subtitleUrls)

        val frenchIndex = runtime.visibleAddonSubtitles.indexOfFirst { it.url == frenchEpisodeOne.url }
        runtime.handlePlayerControlsEvent("selectAddonSubtitle", frenchIndex.toDouble())
        runtime.refreshTracks()

        assertTrue(runtime.isUserExplicitSubtitleSelection)
        assertEquals(frenchEpisodeOne.url, runtime.selectedAddonSubtitle?.url)
        assertEquals(listOf(englishEpisodeOne.url, frenchEpisodeOne.url), controller.subtitleUrls)
    }

    private fun runtime(bingeGroup: String?) = PlayerScreenRuntime(
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
            initialBingeGroup = bingeGroup,
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
        scope = this@PlayerSubtitleSelectionRuntimeTest.scope
        playerSettingsUiState = PlayerSettingsUiState(preferredSubtitleLanguage = "en")
        addonSubtitles = listOf(englishEpisodeOne, frenchEpisodeOne)
        playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false)
        playerController = RecordingController()
        resetIdentityStateIfNeeded()
    }

    private fun subtitle(episode: Int, language: String, addonName: String) = AddonSubtitle(
        id = "$addonName-$language-$episode",
        url = "https://${addonName.lowercase()}.example/episode-$episode-$language.srt",
        language = language,
        display = language,
        addonName = addonName,
    )

    private class RecordingController : PlayerEngineController {
        val subtitleUrls = mutableListOf<String>()
        val subtitleSelections = mutableListOf<Int>()

        override fun setSubtitleUri(url: String) {
            subtitleUrls += url
        }

        override fun selectSubtitleTrack(index: Int) {
            subtitleSelections += index
        }

        override fun getAudioTracks() = emptyList<AudioTrack>()
        override fun getSubtitleTracks() = emptyList<SubtitleTrack>()
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

    companion object {
        private val temporaryRoot: Path =
            Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()

        @BeforeClass
        @JvmStatic
        fun isolateUserHome() {
            // Used by DesktopStorage when no XDG_CONFIG_HOME/APPDATA override is present.
            System.setProperty("user.home", Files.createTempDirectory("nuvio-subtitle-runtime-test").toString())
        }

        private fun storageIsIsolated(): Boolean {
            val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            val configBase = when {
                osName.contains("win") -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                    ?: System.getProperty("user.home")
                osName.contains("mac") -> System.getProperty("user.home")
                else -> System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                    ?: System.getProperty("user.home")
            }
            if (!Paths.get(configBase).toAbsolutePath().normalize().startsWith(temporaryRoot)) return false
            return DesktopStorage.rootDir.toAbsolutePath().normalize().startsWith(temporaryRoot)
        }
    }
}
