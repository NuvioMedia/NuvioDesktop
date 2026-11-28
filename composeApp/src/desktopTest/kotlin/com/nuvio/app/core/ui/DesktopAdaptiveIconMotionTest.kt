package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Square
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.AppScreenTab
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Per-frame icon positions, one entry per tab, in doubled pixels to keep half-pixel centers. */
private typealias IconCenters = List<Int>

class DesktopAdaptiveIconMotionTest {
    @get:Rule
    val compose = createComposeRule()

    private val labels = listOf("Home", "Search", "Library", "Settings")
    private val heroEnabled = mutableStateOf(true)
    private val scrollState = NuvioNavBarScrollState()
    private lateinit var tabs: List<SemanticsNodeInteraction>
    private lateinit var iconColors: List<Color>
    private lateinit var compact: IconCenters

    private val library get() = tabs[2]

    @Test
    fun hoverMovesIconsMonotonically() {
        setContent(scale = 1f, LayoutDirection.Ltr, width = 901)
        checkHoverExpandsAndCollapses()
    }

    @Test
    fun hoverMovesIconsMonotonicallyInNarrowRtlWindow() {
        setContent(scale = 1.25f, LayoutDirection.Rtl, width = 357)
        checkHoverExpandsAndCollapses()
    }

    @Test
    fun interruptedHoverReversesMonotonically() {
        setContent(scale = 1f, LayoutDirection.Ltr, width = 901)
        checkInterruptedHover()
    }

    @Test
    fun interruptedHoverReversesMonotonicallyInNarrowRtlWindow() {
        setContent(scale = 1.25f, LayoutDirection.Rtl, width = 357)
        checkInterruptedHover()
    }

    @Test
    fun scrollMovesIconsMonotonically() {
        setContent(scale = 1f, LayoutDirection.Ltr, width = 901)
        checkScrollExpandsAndCollapses()
    }

    @Test
    fun scrollMovesIconsMonotonicallyInNarrowRtlWindow() {
        setContent(scale = 1.25f, LayoutDirection.Rtl, width = 357)
        checkScrollExpandsAndCollapses()
    }

    private fun checkHoverExpandsAndCollapses() {
        val expansion = hoverExpand()
        assertNotEquals(compact[0], expansion.last()[0], "Home icon did not move")
        assertMonotonic(expansion)
        val collapse = hoverCollapse(from = expansion.last())
        assertMonotonic(collapse)
        assertEquals(compact, collapse.last())
    }

    private fun checkInterruptedHover() {
        val expansion = hoverExpand()
        hoverCollapse(from = expansion.last())

        library.performMouseInput { enter(center) }
        val partial = frames(8)
        assertTrue(partial.last() != compact && partial.last() != expansion.last(), "Hover was not interrupted mid-transition")
        library.performMouseInput { exit() }
        val reversal = frames(24)
        // Mouse exit and recomposition can consume frames of the outgoing tween.
        assertTrue(reversal.first() in expansion.slice(partial.size..partial.size + 2), "Reversal jumped to ${reversal.first()}")
        assertMonotonic(reversal)
        assertEquals(compact, reversal.last())
    }

    private fun checkScrollExpandsAndCollapses() {
        val expanded = hoverExpand().last()
        hoverCollapse(from = expanded)

        compose.runOnIdle {
            scrollState.updateScrollOffset(AppScreenTab.Home, 36f)
            heroEnabled.value = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { scrollState.updateScrollOffset(AppScreenTab.Home, 35f) }
        val expansion = listOf(compact) + frames(24)
        assertMonotonic(expansion)
        assertEquals(expanded, expansion.last())
        compose.runOnIdle { scrollState.updateScrollOffset(AppScreenTab.Home, 36f) }
        val collapse = listOf(expansion.last()) + frames(24)
        assertMonotonic(collapse)
        assertEquals(compact, collapse.last())
    }

    private fun hoverExpand(): List<IconCenters> {
        library.performMouseInput { enter(center) }
        return listOf(compact) + frames(24)
    }

    private fun hoverCollapse(from: IconCenters): List<IconCenters> {
        library.performMouseInput { exit() }
        return listOf(from) + frames(24)
    }

    private fun setContent(scale: Float, direction: LayoutDirection, width: Int) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale), LocalLayoutDirection provides direction) {
                NuvioTheme {
                    Box(Modifier.size(width.dp, 80.dp)) {
                        DesktopNavigationBar(
                            items = labels.mapIndexed { i, label ->
                                FloatingNavigationItem(
                                    label = label,
                                    selected = i == 0,
                                    onClick = {},
                                    // The last tab uses custom content, like the profile avatar.
                                    icon = if (i == 3) null else Icons.Default.Square,
                                    content = if (i == 3) {
                                        { Box(Modifier.size(16.dp).background(Color.Magenta)) }
                                    } else null,
                                )
                            },
                            scrollState = scrollState,
                            isHeroEnabled = heroEnabled.value,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        tabs = labels.map { compose.onNodeWithContentDescription(it) }
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        iconColors = tabs.map {
            val center = it.fetchSemanticsNode().boundsInRoot.center
            pixels[center.x.toInt(), center.y.toInt()]
        }
        compact = iconCenters()
    }

    /** Locates the solid square drawn in each hit target, including the custom avatar. */
    private fun iconCenters(): IconCenters {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        return tabs.mapIndexed { i, tab ->
            val bounds = tab.fetchSemanticsNode().boundsInRoot
            val y = bounds.center.y.toInt()
            val columns = (bounds.left.toInt() until bounds.right.toInt()).filter { x ->
                (-4..4).all { dy -> pixels[x, y + dy] == iconColors[i] }
            }
            val square = columns.windowed(8).filter { it.last() - it.first() == 7 }
            check(square.isNotEmpty()) { "Missing ${labels[i]} icon" }
            square.first().first() + square.last().last()
        }
    }

    private fun frames(count: Int): List<IconCenters> = List(count) {
        compose.mainClock.advanceTimeByFrame()
        iconCenters()
    }

    private fun assertMonotonic(frames: List<IconCenters>) {
        labels.forEachIndexed { i, label ->
            val xs = frames.map { it[i] }
            assertTrue(xs == xs.sorted() || xs == xs.sortedDescending(), "$label reversed at $xs")
        }
    }
}
