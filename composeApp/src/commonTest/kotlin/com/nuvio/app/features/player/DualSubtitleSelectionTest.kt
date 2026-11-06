package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DualSubtitleSelectionTest {
    @Test
    fun bothPreferencesUseBottomAndTop() {
        val result = resolve(listOf(track(0, "en"), track(1, "fr")))
        assertEquals("0", result.primary.trackId())
        assertEquals("1", result.secondary.trackId())
    }

    @Test
    fun missingPrimaryPromotesSecondaryToBottom() {
        val result = resolve(listOf(track(0, "fr")))
        assertEquals("0", result.primary.trackId())
        assertNull(result.secondary)
    }

    @Test
    fun missingSecondaryLeavesTopEmpty() {
        val result = resolve(listOf(track(0, "en")))
        assertEquals("0", result.primary.trackId())
        assertNull(result.secondary)
    }

    @Test
    fun unrelatedLanguageIsNeverAutomaticallySelected() {
        val result = resolve(listOf(track(0, "de")))
        assertNull(result.primary)
        assertNull(result.secondary)
    }

    @Test
    fun samePreferenceDoesNotSelectTwoTracks() {
        val result = resolve(listOf(track(0, "en"), track(1, "en")), secondary = "eng")
        assertEquals("0", result.primary.trackId())
        assertNull(result.secondary)
    }

    @Test
    fun audioMatchingPrimaryUsesForcedAndSecondaryUsesNormal() {
        val result = resolve(listOf(track(0, "en"), track(1, "en", forced = true),
            track(2, "fr"), track(3, "fr", forced = true)), forced = true)
        assertEquals("1", result.primary.trackId())
        assertEquals("2", result.secondary.trackId())
    }

    @Test
    fun audioMatchingSecondaryUsesForcedWhilePrimaryRemainsNormal() {
        val result = resolve(listOf(track(0, "en"), track(1, "fr"), track(2, "fr", forced = true)),
            forced = true, audio = "fr")
        assertEquals("0", result.primary.trackId())
        assertEquals("2", result.secondary.trackId())
    }

    @Test
    fun missingForcedPrimaryPromotesOtherLanguageWithoutFullSubtitleFallback() {
        val result = resolve(listOf(track(0, "en"), track(1, "fr")), forced = true)
        assertEquals("1", result.primary.trackId())
        assertNull(result.secondary)
    }

    @Test
    fun forcedTracksAreIgnoredWhenForcedSettingIsOff() {
        val result = resolve(listOf(track(0, "en", forced = true), track(1, "en"), track(2, "fr")))
        assertEquals("1", result.primary.trackId())
        assertEquals("2", result.secondary.trackId())
    }

    @Test
    fun forcedAddonCanFillPrimaryWithoutReplacingNormalSecondary() {
        val addon = AddonSubtitle("en-forced", "https://example.com/en-forced.srt", "en", "English forced")
        val result = resolve(listOf(track(0, "en"), track(1, "fr")), addons = listOf(addon), forced = true)
        assertEquals(addon, (result.primary as SubtitleSelectionOption.Addon).subtitle)
        assertEquals("1", result.secondary.trackId())
    }

    @Test
    fun preferredAddonBeatsSecondaryBuiltIn() {
        val addon = AddonSubtitle("en", "https://example.com/en.srt", "en", "English")
        val result = resolve(listOf(track(0, "fr")), addons = listOf(addon))
        assertEquals(addon, (result.primary as SubtitleSelectionOption.Addon).subtitle)
        assertEquals("0", result.secondary.trackId())
    }

    @Test
    fun bitmapSecondaryIsSkippedWhilePrimaryStillWorks() {
        val result = resolve(listOf(track(0, "en"), track(1, "fr", codec = "hdmv_pgs_subtitle")))
        assertEquals("0", result.primary.trackId())
        assertNull(result.secondary)
    }

    @Test
    fun soleBitmapLanguageIsNotPromotedToPrimary() {
        val result = resolve(listOf(track(0, "fr", codec = "hdmv_pgs_subtitle")))
        assertNull(result.primary)
        assertNull(result.secondary)
    }

    @Test
    fun bitmapPrimaryIsSkippedForMatchingTextTrack() {
        val result = resolve(listOf(track(0, "en", codec = "hdmv_pgs_subtitle"), track(1, "en"), track(2, "fr")))
        assertEquals("1", result.primary.trackId())
        assertEquals("2", result.secondary.trackId())
    }

    @Test
    fun deviceLanguageIsResolvedBeforeAssigningSecondary() {
        val result = resolve(listOf(track(0, "en"), track(1, "fr")), preferred = SubtitleLanguageOption.DEVICE)
        assertEquals("0", result.primary.trackId())
        assertEquals("1", result.secondary.trackId())
    }

    @Test
    fun noneKeepsAutomaticSubtitlesOff() {
        val result = resolve(listOf(track(0, "en"), track(1, "fr")), preferred = SubtitleLanguageOption.NONE)
        assertNull(result.primary)
        assertNull(result.secondary)
    }

    private fun resolve(
        tracks: List<SubtitleTrack>,
        preferred: String = "en",
        secondary: String? = "fr",
        addons: List<AddonSubtitle> = emptyList(),
        forced: Boolean = false,
        audio: String = "en",
    ) = resolveDualSubtitleSelection(tracks, addons, preferred, secondary, listOf("en"),
        AudioTrack(0, "audio", audio, audio, true), listOf(audio), forced)

    private fun track(index: Int, language: String, forced: Boolean = false, codec: String = "subrip") =
        SubtitleTrack(index, index.toString(), language, language, isForced = forced, codec = codec)

    private fun SubtitleSelectionOption?.trackId() = (this as? SubtitleSelectionOption.BuiltIn)?.track?.id
}
