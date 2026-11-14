package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.random_episode_title
import org.jetbrains.compose.resources.stringResource
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailActionsShuffleDesktopTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun shuffleActionIsVisibleBesidePlayWithoutOpeningMore() {
        var clicks = 0
        lateinit var label: String
        compose.setContent {
            NuvioTheme {
                label = stringResource(Res.string.random_episode_title)
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    DetailActions(
                        playLabel = "Play", playEnabled = true, isSaved = false, isWatched = false,
                        isTablet = true, shuffleEnabled = false, onPlayClick = {}, onPlayLongClick = null,
                        onShuffleClick = { clicks++ }, onWatchedClick = {}, onSaveClick = {}, onSaveLongClick = null,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(label).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
