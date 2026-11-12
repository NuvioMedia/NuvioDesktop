package com.nuvio.app.features.details

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetaScreenSettingsRepositoryTest {

    @BeforeTest
    fun setUp() {
        MetaScreenSettingsRepository.clearLocalState()
        MetaScreenSettingsStorage.savePayload("")
    }

    @AfterTest
    fun tearDown() {
        MetaScreenSettingsRepository.clearLocalState()
        MetaScreenSettingsStorage.savePayload("")
    }

    @Test
    fun `default values have blur unwatched disabled but thumbnails enabled by default`() {
        MetaScreenSettingsRepository.ensureLoaded()
        val state = MetaScreenSettingsRepository.uiState.value

        assertFalse(state.blurUnwatchedEpisodes)
        assertTrue(state.blurEpisodeThumbnails)
        assertFalse(state.blurEpisodeTitles)
        assertFalse(state.blurEpisodeDescriptions)
    }

    @Test
    fun `sub-toggles update state independently and persist through reload`() {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.setBlurUnwatchedEpisodes(true)
        MetaScreenSettingsRepository.setBlurEpisodeThumbnails(true)
        MetaScreenSettingsRepository.setBlurEpisodeTitles(true)
        MetaScreenSettingsRepository.setBlurEpisodeDescriptions(true)

        val updatedState = MetaScreenSettingsRepository.uiState.value
        assertTrue(updatedState.blurUnwatchedEpisodes)
        assertTrue(updatedState.blurEpisodeThumbnails)
        assertTrue(updatedState.blurEpisodeTitles)
        assertTrue(updatedState.blurEpisodeDescriptions)

        // Clear in-memory and reload from persistence
        MetaScreenSettingsRepository.onProfileChanged()
        val reloadedState = MetaScreenSettingsRepository.uiState.value
        assertTrue(reloadedState.blurUnwatchedEpisodes)
        assertTrue(reloadedState.blurEpisodeThumbnails)
        assertTrue(reloadedState.blurEpisodeTitles)
        assertTrue(reloadedState.blurEpisodeDescriptions)
    }

    @Test
    fun `legacy payload without sub-toggles preserves default values for backwards compatibility`() {
        MetaScreenSettingsStorage.savePayload("""{"blur_unwatched_episodes":true}""")
        MetaScreenSettingsRepository.onProfileChanged()

        val state = MetaScreenSettingsRepository.uiState.value
        assertTrue(state.blurUnwatchedEpisodes)
        assertTrue(state.blurEpisodeThumbnails)
        assertFalse(state.blurEpisodeTitles)
        assertFalse(state.blurEpisodeDescriptions)
    }

    @Test
    fun `reset to defaults resets all sub-toggles`() {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.setBlurUnwatchedEpisodes(true)
        MetaScreenSettingsRepository.setBlurEpisodeThumbnails(false)
        MetaScreenSettingsRepository.setBlurEpisodeTitles(true)
        MetaScreenSettingsRepository.setBlurEpisodeDescriptions(true)

        MetaScreenSettingsRepository.resetToDefaults()

        val state = MetaScreenSettingsRepository.uiState.value
        assertFalse(state.blurUnwatchedEpisodes)
        assertTrue(state.blurEpisodeThumbnails)
        assertFalse(state.blurEpisodeTitles)
        assertFalse(state.blurEpisodeDescriptions)
    }
}
