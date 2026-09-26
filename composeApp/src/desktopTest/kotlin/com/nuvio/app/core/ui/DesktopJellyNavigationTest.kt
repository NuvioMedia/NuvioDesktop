package com.nuvio.app.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopJellyNavigationTest {
    @get:Rule
    val compose = createComposeRule()
    private val selected = mutableIntStateOf(0)
    private val clicks = mutableListOf<Int>()
    private var profileOpened = false

    @Test
    fun mouseSelectionNavigatesOnceAndKeepsDesktopLabels() {
        setContent()
        compose.onNodeWithContentDescription("Library").performMouseInput {
            enter(center)
            click()
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Library").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(2), clicks) }
        savePreview("expanded")
    }

    @Test
    fun hoveringExpandsHomeAndLeavingCollapsesIt() {
        setContent()
        compose.waitForIdle()
        val home = compose.onNodeWithContentDescription("Home")
        val compactWidth = home.fetchSemanticsNode().boundsInRoot.width
        savePreview("compact")
        home.performMouseInput { enter(center) }
        compose.waitForIdle()
        val expandedWidth = home.fetchSemanticsNode().boundsInRoot.width
        assertTrue(expandedWidth > compactWidth * 1.5f)
        home.performMouseInput { exit() }
        compose.waitForIdle()
        assertEquals(compactWidth, home.fetchSemanticsNode().boundsInRoot.width, 2f)
    }

    @Test
    fun profileHoldOpensTheSwitcherWithoutNavigating() {
        setContent(withProfile = true)
        compose.onNodeWithContentDescription("Settings").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Home").assertIsSelected()
        compose.runOnIdle {
            assertTrue(profileOpened)
            assertTrue(clicks.isEmpty())
        }
    }

    private fun setContent(withProfile: Boolean = false) {
        val labels = listOf("Home", "Search", "Library", "Settings")
        val icons = listOf(Icons.Default.Home, Icons.Default.Search, Icons.Default.VideoLibrary, Icons.Default.Settings)
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(640.dp, 220.dp).background(Color(0xFF151619))) {
                    DesktopNavigationBar(
                        items = labels.mapIndexed { index, label ->
                            FloatingNavigationItem(
                                label = label,
                                selected = selected.intValue == index,
                                onClick = {
                                    clicks += index
                                    selected.intValue = index
                                },
                                icon = if (withProfile && index == 3) null else icons[index],
                                content = if (withProfile && index == 3) {
                                    { onClick ->
                                        Box(
                                            Modifier.size(26.dp).background(Color.Gray)
                                                .clickable(onClick = onClick)
                                                .pointerInput(Unit) {
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = { profileOpened = true },
                                                        onDrag = { change, _ -> change.consume() },
                                                    )
                                                },
                                        )
                                    }
                                } else null,
                            )
                        },
                        isHeroEnabled = true,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }

    private fun savePreview(name: String) {
        val directory = File("build/reports/jelly-navigation").apply { mkdirs() }
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }
}
