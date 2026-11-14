package com.nuvio.app.features.shuffle

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.watched.watchedItemKey
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.random_episode_title
import nuvio.composeapp.generated.resources.random_episode_include_watched
import nuvio.composeapp.generated.resources.random_episode_unwatched
import nuvio.composeapp.generated.resources.shuffle_this_season
import nuvio.composeapp.generated.resources.shuffle_stop
import org.junit.Rule
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeShuffleSheetDesktopTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun thisSeasonOnlyPreviewsAnEpisodeFromTheSelectedSeason() {
        val details = MetaDetails("show", "series", "Show", videos = listOf(
            MetaVideo("show:1:1", "First season episode", season = 1, episode = 1),
            MetaVideo("show:2:1", "Second season episode", season = 2, episode = 1),
        ))
        val labels = show(details, currentSeason = 2)

        compose.onNodeWithText(labels.thisSeason).performClick()
        compose.onNodeWithText("Second season episode").assertIsDisplayed()
    }

    @Test
    fun activeShuffleCanBeStoppedWithoutEpisodeMetadata() {
        val labels = show(MetaDetails("show", "series", "Show"), currentSeason = null,
            settings = EpisodeShuffleSettings(enabled = true))

        compose.onNodeWithText(labels.stop).assertIsDisplayed()
    }

    @Test
    fun continuousShuffleCanStillBeEnabledFromThePreview() {
        val details = MetaDetails("show", "series", "Show", videos = listOf(
            MetaVideo("show:1:1", "First episode", season = 1, episode = 1),
        ))
        var saved: EpisodeShuffleSettings? = null
        val labels = show(details, currentSeason = 1, persistentAvailable = true,
            onSave = { saved = it; true })

        compose.onNodeWithText(labels.thisSeason).performClick()
        compose.onNodeWithText(labels.unwatched).assertExists()
        compose.onNodeWithText(labels.allEpisodes).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(EpisodeShuffleSettings(true, true), saved) }
    }

    @Test
    fun watchedOnlySeriesCannotEnableEmptyUnwatchedShuffle() {
        val details = MetaDetails("show", "series", "Show", videos = listOf(
            MetaVideo("show:1:1", "First episode", season = 1, episode = 1),
        ))
        val labels = show(details, currentSeason = 1, persistentAvailable = true,
            watchedKeys = setOf(watchedItemKey("series", "show", 1, 1)))

        compose.onNodeWithText(labels.thisSeason).performClick()
        compose.onNodeWithText(labels.unwatched).assertIsNotEnabled()
        compose.onNodeWithText(labels.allEpisodes).performScrollTo().assertIsDisplayed()
    }

    private data class Labels(
        val title: String,
        val thisSeason: String,
        val stop: String,
        val allEpisodes: String,
        val unwatched: String,
    )

    private fun show(
        details: MetaDetails,
        currentSeason: Int?,
        settings: EpisodeShuffleSettings = EpisodeShuffleSettings(),
        persistentAvailable: Boolean = false,
        onSave: (EpisodeShuffleSettings) -> Boolean = { true },
        watchedKeys: Set<String> = emptySet(),
    ): Labels {
        lateinit var labels: Labels
        compose.setContent {
            NuvioTheme {
                labels = Labels(stringResource(Res.string.random_episode_title),
                    stringResource(Res.string.shuffle_this_season), stringResource(Res.string.shuffle_stop),
                    stringResource(Res.string.random_episode_include_watched),
                    stringResource(Res.string.random_episode_unwatched))
                EpisodeShuffleSheet(details, currentSeason, persistentAvailable, settings, onSave,
                    watchedKeys, emptyList(), false,
                    onDismiss = {}, onPlay = {}, onPlayManually = null, onStartFromBeginning = {})
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(labels.title)).fetchSemanticsNodes().isNotEmpty()
        }
        return labels
    }
}
