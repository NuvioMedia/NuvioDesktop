package com.nuvio.app.features.home.components

import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeHeroSectionTest {

    @Test
    fun `mobile hero height follows viewport height when provided`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 844f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(692.08f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `tablet hero height remains width driven even with viewport height`() {
        val layout = homeHeroLayout(
            maxWidthDp = 840f,
            viewportHeightDp = 1200f,
        )

        assertEquals(true, layout.isTablet)
        assertEquals(386.4f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `desktop hero keeps the existing full bleed layout at sixteen by nine`() {
        val layout = homeHeroLayout(
            maxWidthDp = 2560f,
            viewportHeightDp = 1440f,
            preferDesktopLayout = true,
        )

        assertEquals(660f, layout.heroHeight.value, 0.001f)
        assertEquals(2560f, layout.contentContainerMaxWidth.value, 0.001f)
        assertEquals(32f, layout.contentHorizontalPadding.value, 0.001f)
        assertEquals(40f, layout.contentVerticalPadding.value, 0.001f)
        assertEquals(160f, layout.topFadeHeight.value, 0.001f)
        assertEquals(300f, layout.bottomFadeHeight.value, 0.001f)
        assertEquals(1f, layout.backgroundMotionStrength, 0.001f)
    }

    @Test
    fun `desktop hero becomes full bleed and full height at thirty two by nine`() {
        val layout = homeHeroLayout(
            maxWidthDp = 3840f,
            viewportHeightDp = 1080f,
            preferDesktopLayout = true,
        )

        assertEquals(1080f, layout.heroHeight.value, 0.001f)
        assertEquals(3840f, layout.contentContainerMaxWidth.value, 0.001f)
        assertEquals(120f, layout.contentHorizontalPadding.value, 0.001f)
        assertEquals(192f, layout.contentVerticalPadding.value, 0.001f)
        assertEquals(160f, layout.topFadeHeight.value, 0.001f)
        assertEquals(300f, layout.bottomFadeHeight.value, 0.001f)
        assertEquals(0f, layout.backgroundMotionStrength, 0.001f)
    }

    @Test
    fun `mobile hero height leaves room for continue watching card section`() {
        val viewportHeight = 844f
        val continueWatchingLayout = rememberContinueWatchingLayout(maxWidthDp = 390f)
        val continueWatchingHeight = continueWatchingSectionHeightEstimate(
            style = ContinueWatchingSectionStyle.Card,
            layout = continueWatchingLayout,
            basePosterWidthDp = 110,
        )
        val reserveHeight = continueWatchingHeroViewportReserveHeight(
            style = ContinueWatchingSectionStyle.Card,
            layout = continueWatchingLayout,
            basePosterWidthDp = 110,
        )
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = viewportHeight,
            mobileBelowSectionHeightHintDp = reserveHeight.value,
        )

        assertEquals(24f, viewportHeight - layout.heroHeight.value - continueWatchingHeight.value, 0.001f)
    }

    @Test
    fun `mobile hero can shrink below default minimum to fit short viewport`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 568f,
            mobileBelowSectionHeightHintDp = 300f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(268f, layout.heroHeight.value, 0.001f)
    }
}
