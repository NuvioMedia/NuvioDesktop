package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertTrue

class SubtitleLanguageCodeMatchingTest {

    @Test
    fun norwegianVarietyTagsMatchNorwegian() {
        assertTrue(SubtitleLanguageMatching.matchesLanguageCode("nob", "no"))
        assertTrue(SubtitleLanguageMatching.matchesLanguageCode("nno", "no"))
        assertTrue(SubtitleLanguageMatching.matchesLanguageCode("nb", "no"))
        assertTrue(SubtitleLanguageMatching.matchesLanguageCode("nb-NO", "no"))
    }

    @Test
    fun containerTagOfLanguageMissingFromSettingsListMatches() {
        assertTrue(SubtitleLanguageMatching.matchesLanguageCode("kur", "ku"))
        assertTrue(SubtitleLanguageMatching.matchesLanguageCode("lat", "la"))
    }
}
