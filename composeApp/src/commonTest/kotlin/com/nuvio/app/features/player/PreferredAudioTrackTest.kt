package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PreferredAudioTrackTest {

    @Test
    fun threeLetterContainerTagMatchesTwoLetterPreference() {
        val tracks = listOf(
            AudioTrack(0, "1", "English 5.1", "eng"),
            AudioTrack(1, "2", "CZ dabing", "cze"),
        )

        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("cs", "sk")))
    }

    @Test
    fun primaryPreferenceWinsOverEarlierSecondaryTrack() {
        val tracks = listOf(
            AudioTrack(0, "1", "SK dabing", "slo"),
            AudioTrack(1, "2", "CZ dabing", "ces"),
        )

        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("cs", "sk")))
    }

    @Test
    fun secondaryPreferenceIsUsedWhenPrimaryIsMissing() {
        val tracks = listOf(
            AudioTrack(0, "1", "English", "eng"),
            AudioTrack(1, "2", "SK dabing", "slk"),
        )

        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("cs", "sk")))
    }

    @Test
    fun regionalTrackMatchesBaseLanguagePreference() {
        val tracks = listOf(
            AudioTrack(0, "1", "English", "en"),
            AudioTrack(1, "2", "Portuguese Brazil", "pt-BR"),
        )

        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("pt")))
    }

    @Test
    fun noMatchingOrUntaggedTrackKeepsPlayerDefault() {
        val tracks = listOf(
            AudioTrack(0, "1", "English", "eng"),
            AudioTrack(1, "2", "Unknown", null),
        )

        assertEquals(-1, findPreferredAudioTrackIndex(tracks, listOf("cs", "sk")))
    }

    @Test
    fun bokmalTrackMatchesNorwegianOriginalLanguage() {
        val tracks = listOf(
            AudioTrack(0, "1", "Portuguese", "por"),
            AudioTrack(1, "2", "Norwegian", "nob"),
        )

        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("no")))
    }

    @Test
    fun varietyTagsFromMpvAndMedia3MatchTheirLanguage() {
        fun pick(tag: String, preference: String) = findPreferredAudioTrackIndex(
            listOf(AudioTrack(0, "1", "English", "eng"), AudioTrack(1, "2", "Original", tag)),
            listOf(preference),
        )

        assertEquals(1, pick("nb", "no"))
        assertEquals(1, pick("no-nob", "no"))
        assertEquals(1, pick("cmn", "zh"))
        assertEquals(1, pick("hbs-srp", "sr"))
        assertEquals(1, pick("ms-ind", "id"))
        assertEquals(-1, pick("ms-ind", "ms"))
    }

    @Test
    fun cantoneseTrackIsNotTakenForMandarin() {
        val tracks = listOf(
            AudioTrack(0, "1", "Cantonese", "yue"),
            AudioTrack(1, "2", "Mandarin", "cmn"),
        )

        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("zh")))
        assertEquals(0, findPreferredAudioTrackIndex(tracks, listOf(resolveContentLanguage("cn", country = null)!!)))
    }

    @Test
    fun containerTagOfLanguageMissingFromSettingsListMatches() {
        val tracks = listOf(
            AudioTrack(0, "1", "English", "eng"),
            AudioTrack(1, "2", "Kurdî", "kur"),
        )

        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("ku")))
    }
}
