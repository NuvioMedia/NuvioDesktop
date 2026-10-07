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
}
