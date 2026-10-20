package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PosterCardClickTest {
    @get:Rule
    val compose = createComposeRule()

    private var clicks = 0
    private var actions = 0
    private var clickAnchor: PosterZoomAnchor? = null
    private var actionAnchor: PosterZoomAnchor? = null

    @After
    fun clearAnchor() {
        PosterZoomAnchorHolder.consume()
        PosterZoomOverlayCoordinator.hide()
    }

    @Test
    fun rightClickOpensActionsWithoutNavigating() {
        setContent()

        compose.onNodeWithTag("poster").performMouseInput { click(button = MouseButton.Secondary) }

        compose.runOnIdle {
            assertEquals(1, actions)
            assertEquals(0, clicks)
            assertNull(clickAnchor)
            assertPosterAnchor(actionAnchor)
        }
    }

    @Test
    fun rightClickKeepsPosterAnchorWithHoverScaleDisabled() {
        setContent(hoverScaleEnabled = false)

        compose.onNodeWithTag("poster").performMouseInput { click(button = MouseButton.Secondary) }

        compose.runOnIdle {
            assertEquals(1, actions)
            assertEquals(0, clicks)
            assertPosterAnchor(actionAnchor)
        }
    }

    @Test
    fun leftClickNavigatesWithoutOpeningActions() {
        setContent()

        compose.onNodeWithTag("poster").performMouseInput { click() }

        compose.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(0, actions)
            assertPosterAnchor(clickAnchor)
        }
    }

    @Test
    fun longPressStillOpensActionsWithoutNavigating() {
        setContent()

        compose.onNodeWithTag("poster").performTouchInput { longClick() }

        compose.runOnIdle {
            assertEquals(1, actions)
            assertEquals(0, clicks)
            assertPosterAnchor(actionAnchor)
        }
    }

    private fun setContent(hoverScaleEnabled: Boolean? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalPosterClickAnchor provides { clickAnchor = it }) {
                val modifier = Modifier.size(100.dp, 150.dp).background(Color.Blue).testTag("poster")
                val onClick: () -> Unit = { clicks++ }
                val onLongClick = {
                    actions++
                    actionAnchor = PosterZoomAnchorHolder.consume()
                }
                Box(
                    if (hoverScaleEnabled == null) {
                        modifier.posterCardClickable(
                            onClick = onClick,
                            onLongClick = onLongClick,
                            zoomImageUrl = "poster.jpg",
                            zoomCornerRadius = 12.dp,
                        )
                    } else {
                        modifier.posterCardClickable(
                            onClick = onClick,
                            onLongClick = onLongClick,
                            zoomImageUrl = "poster.jpg",
                            zoomCornerRadius = 12.dp,
                            hoverScaleEnabled = hoverScaleEnabled,
                        )
                    },
                )
            }
        }
    }

    private fun assertPosterAnchor(anchor: PosterZoomAnchor?) {
        assertNotNull(anchor)
        assertEquals("poster.jpg", anchor.imageUrl)
        assertEquals(12.dp, anchor.cornerRadius)
        assertTrue(anchor.boundsInRoot.width > 0f)
        assertTrue(anchor.boundsInRoot.height > 0f)
        assertTrue(assertNotNull(anchor.source).canLift)
    }
}
