package com.nuvio.app.features.player

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AddonSubtitleLoaderTest {
    private val request = SubtitleAddonRequest("https://addon.example/subtitles/series/episode.json", "example.addon", "Example Addon")

    @Test fun keepsTrackLabelSeparateFromLanguageAndAddonName() = runTest {
        val item = parseAddonSubtitles("""{"subtitles":[{"id":"track-1","url":"https://cdn.example/subtitle.srt","lang":"nld","label":"📄 OSv3 ⏳","name":"wrong"}]}""", request).single()
        assertEquals("📄 OSv3 ⏳", item.display)
        assertEquals("📄 OSv3 ⏳", item.label)
        assertEquals("nl", item.language)
        assertEquals("Example Addon", item.addonName)
        assertEquals("track-1", item.id)
        assertEquals("https://cdn.example/subtitle.srt", item.url)
    }

    @Test fun acceptsOtherAddonsNameAndTitleAliases() = runTest {
        val items = parseAddonSubtitles("""{"subtitles":[{"url":"https://cdn.example/a.srt","lang":"tur","name":"⚡AUTO"},{"url":"https://cdn.example/b.srt","lang":"ara","title":"DL"}]}""", request)
        assertEquals(listOf("⚡AUTO", "DL"), items.map { it.display })
        assertEquals(listOf("⚡AUTO", "DL"), items.map { it.label })
        assertEquals(listOf("tr", "ar"), items.map { it.language })
    }

    @Test fun malformedOptionalLabelDoesNotDiscardValidTracks() = runTest {
        val item = parseAddonSubtitles("""{"subtitles":[{"url":"https://cdn.example/a.srt","lang":"eng","label":{"invalid":true},"name":"Safe label"}]}""", request).single()
        assertEquals("Safe label", item.display)
        assertEquals("Safe label", item.label)
        assertEquals("en", item.language)
    }

    @Test fun retainsExistingIdLabelFallbackWithoutExplicitTrackLabel() = runTest {
        val item = parseAddonSubtitles("""{"subtitles":[{"id":"provider-track","url":"https://cdn.example/a.srt","lang":"eng"}]}""", request).single()
        assertEquals("provider-track", item.label)
        assertEquals("provider-track", item.id)
    }
}
