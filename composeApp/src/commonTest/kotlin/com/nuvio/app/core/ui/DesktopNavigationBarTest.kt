package com.nuvio.app.core.ui

import com.nuvio.app.features.settings.DesktopNavigationLayout
import com.nuvio.app.features.settings.NavBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopNavigationBarTest {
    @Test
    fun desktopLayoutPreferencesKeepTopBarAndSidebar() {
        assertEquals(DesktopNavigationLayout.TopBar, DesktopNavigationLayout.fromName("TopBar"))
        assertEquals(DesktopNavigationLayout.TopBar, DesktopNavigationLayout.fromName("topbar"))
        assertEquals(DesktopNavigationLayout.Sidebar, DesktopNavigationLayout.fromName("Sidebar"))
        assertEquals(DesktopNavigationLayout.Default, DesktopNavigationLayout.fromName(null))
    }

    @Test
    fun adaptiveHomeWithHeroExpandsForHoverAndProfilePopup() {
        assertEquals(0f, labels(hero = true))
        assertEquals(1f, labels(hero = true, hovered = true))
        assertEquals(1f, labels(hero = true, popup = true))
        assertEquals(0f, labels(hero = true, scrolled = true))
    }

    @Test
    fun adaptiveHomeWithoutHeroCollapsesAfterScrolling() {
        assertEquals(1f, labels())
        assertEquals(0f, labels(scrolled = true))
        assertEquals(1f, labels(scrolled = true, hovered = true))
    }

    @Test
    fun otherTabsKeepTheirLabelsWhileScrolling() {
        assertEquals(1f, labels(home = false, scrolled = true))
    }

    @Test
    fun explicitStylesOverrideHoverAndScrolling() {
        assertEquals(0f, labels(style = NavBarStyle.COMPACT, hovered = true, popup = true))
        assertEquals(1f, labels(style = NavBarStyle.EXPANDED, hero = true, scrolled = true))
    }

    private fun labels(
        style: NavBarStyle = NavBarStyle.ADAPTIVE,
        home: Boolean = true,
        hero: Boolean = false,
        hovered: Boolean = false,
        popup: Boolean = false,
        scrolled: Boolean = false,
    ): Float = desktopNavigationLabelFraction(style, home, hero, hovered, popup, scrolled)
}
