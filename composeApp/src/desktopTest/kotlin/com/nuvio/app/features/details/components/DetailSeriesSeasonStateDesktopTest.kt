package com.nuvio.app.features.details.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.episodes_season
import org.jetbrains.compose.resources.stringResource
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailSeriesSeasonStateDesktopTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectedSeasonSurvivesEpisodeSectionRemount() {
        val details = MetaDetails("show", "series", "Show", videos = listOf(
            MetaVideo("show:1:1", "First season episode", season = 1, episode = 1),
            MetaVideo("show:2:1", "Second season episode", season = 2, episode = 1),
        ))
        var selected by mutableStateOf<Int?>(null)
        var visible by mutableStateOf(true)
        lateinit var seasonTwo: String
        compose.setContent {
            NuvioTheme {
                seasonTwo = stringResource(Res.string.episodes_season, 2)
                if (visible) DetailSeriesContent(details, selected, onSeasonSelect = { selected = it })
            }
        }

        compose.onNodeWithText(seasonTwo).performClick()
        compose.runOnIdle { assertEquals(2, selected); visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.onNodeWithText("Second season episode").assertIsDisplayed()
    }
}
