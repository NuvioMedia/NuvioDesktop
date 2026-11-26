package com.nuvio.app.features.settings

import com.nuvio.app.features.player.AndroidPlaybackEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsNavigationTest {
    @Test
    fun layoutChildrenReturnToLayout() {
        listOf(
            SettingsPage.Homescreen,
            SettingsPage.MetaScreen,
            SettingsPage.ContinueWatching,
            SettingsPage.PosterCustomization,
            SettingsPage.Streams,
        ).forEach { page ->
            assertEquals(SettingsPage.Layout, page.previousPage())
            assertEquals(SettingsCategory.Look, page.category)
        }
        assertEquals(SettingsPage.Root, SettingsPage.Layout.previousPage())
    }

    @Test
    fun playbackChildrenReturnToPlaybackInTheWatchGroup() {
        PlaybackSection.entries.forEach { section ->
            assertEquals(SettingsPage.Playback, section.page.previousPage())
            assertEquals(SettingsCategory.Watch, section.page.category)
        }
        assertEquals(SettingsPage.Root, SettingsPage.Playback.previousPage())
    }

    @Test
    fun bufferOptionsFollowThePlatformAndPlaybackEngine() {
        AndroidPlaybackEngine.entries.forEach { engine ->
            val android = visiblePlaybackSections(
                ios = false,
                engine = engine,
                p2pVisible = false,
                externalPlayerSupported = true,
                desktop = false,
            )
            val ios = visiblePlaybackSections(
                ios = true,
                engine = engine,
                p2pVisible = false,
                externalPlayerSupported = true,
                desktop = false,
            )
            assertEquals(engine != AndroidPlaybackEngine.Libmpv, PlaybackSection.Buffer in android)
            assertFalse(PlaybackSection.Buffer in ios)
            assertFalse(PlaybackSection.P2p in android)
            assertFalse(PlaybackSection.P2p in ios)
            assertTrue(PlaybackSection.Interface in android)
            assertTrue(PlaybackSection.Interface in ios)
        }
        assertTrue(
            PlaybackSection.P2p in visiblePlaybackSections(
                ios = false,
                engine = AndroidPlaybackEngine.Auto,
                p2pVisible = true,
                externalPlayerSupported = true,
                desktop = false,
            ),
        )
    }

    @Test
    fun playerPageIsHiddenOnDesktopWithoutExternalPlayerSupport() {
        AndroidPlaybackEngine.entries.forEach { engine ->
            val unsupported = visiblePlaybackSections(
                ios = false,
                engine = engine,
                p2pVisible = false,
                externalPlayerSupported = false,
                desktop = true,
            )
            val supported = visiblePlaybackSections(
                ios = false,
                engine = engine,
                p2pVisible = false,
                externalPlayerSupported = true,
                desktop = true,
            )
            assertFalse(PlaybackSection.Player in unsupported)
            assertTrue(PlaybackSection.Player in supported)
            assertTrue(PlaybackSection.Interface in unsupported)
        }
    }

    @Test
    fun playerPageStaysOnMobile() {
        listOf(true, false).forEach { externalPlayerSupported ->
            AndroidPlaybackEngine.entries.forEach { engine ->
                val android = visiblePlaybackSections(
                    ios = false,
                    engine = engine,
                    p2pVisible = false,
                    externalPlayerSupported = externalPlayerSupported,
                    desktop = false,
                )
                assertTrue(PlaybackSection.Player in android)
            }
        }
        val ios = visiblePlaybackSections(
            ios = true,
            engine = AndroidPlaybackEngine.Auto,
            p2pVisible = false,
            externalPlayerSupported = true,
            desktop = false,
        )
        assertTrue(PlaybackSection.Player in ios)
    }

    @Test
    fun playerPageHasContentOnlyWithExternalPlayerOrDecoderSettings() {
        assertFalse(playbackPlayerPageHasContent(ios = false, desktop = true, externalPlayerSupported = false))
        assertTrue(playbackPlayerPageHasContent(ios = false, desktop = true, externalPlayerSupported = true))
        assertTrue(playbackPlayerPageHasContent(ios = false, desktop = false, externalPlayerSupported = false))
        assertTrue(playbackPlayerPageHasContent(ios = false, desktop = false, externalPlayerSupported = true))
        assertFalse(playbackPlayerPageHasContent(ios = true, desktop = false, externalPlayerSupported = false))
        assertTrue(playbackPlayerPageHasContent(ios = true, desktop = false, externalPlayerSupported = true))
    }

    @Test
    fun oldTabletCategoriesRestoreToTheirNewGroups() {
        assertEquals(SettingsCategory.You, settingsCategoryForName("Account"))
        assertEquals(SettingsCategory.Look, settingsCategoryForName("General"))
        assertEquals(SettingsCategory.System, settingsCategoryForName("About"))
        assertEquals(SettingsCategory.System, settingsCategoryForName("Advanced"))
        assertEquals(SettingsCategory.Services, settingsCategoryForName("Services"))
        assertEquals(SettingsCategory.Look, settingsCategoryForName("unknown"))
    }
}
