package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.desktop.DesktopHostOs
import org.junit.Assume.assumeTrue
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DesktopWindowInsetsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun titleBarClearanceSurvivesScalingAndFullscreenWithoutMovingTheBackdropOrResettingContent() {
        assumeTrue(DesktopHostOs.current == DesktopHostOs.MACOS)
        val fullscreen = mutableStateOf(false)
        val uiScale = mutableFloatStateOf(1f)
        var contentIdentity: Any? = null

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                ProvideDesktopWindowInsets(isFullscreen = fullscreen.value) {
                    val identity = remember { Any() }
                    SideEffect { contentIdentity = identity }
                    NuvioTheme(desktopUiScale = uiScale.floatValue) {
                        Box(Modifier.fillMaxSize().testTag("backdrop")) {
                            Box(Modifier.statusBarsPadding()) {
                                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                                    Box(Modifier.size(40.dp).testTag("control"))
                                }
                            }
                        }
                    }
                }
            }
        }

        assertTop("backdrop", 0f)
        assertTop("control", 56f)
        val initialIdentity = contentIdentity
        val initialControlWidth = compose.onNodeWithTag("control").fetchSemanticsNode().boundsInRoot.width

        compose.runOnIdle { uiScale.floatValue = 1.18f }
        assertTop("control", 56f)
        assertEquals(
            initialControlWidth * 1.18f,
            compose.onNodeWithTag("control").fetchSemanticsNode().boundsInRoot.width,
            1f,
        )

        compose.runOnIdle { fullscreen.value = true }
        assertTop("backdrop", 0f)
        assertTop("control", 0f)
        assertSame(initialIdentity, contentIdentity)

        compose.runOnIdle { fullscreen.value = false }
        assertTop("backdrop", 0f)
        assertTop("control", 56f)
        assertSame(initialIdentity, contentIdentity)
    }

    private fun assertTop(tag: String, expected: Float) {
        assertEquals(expected, compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top, 1f)
    }
}
