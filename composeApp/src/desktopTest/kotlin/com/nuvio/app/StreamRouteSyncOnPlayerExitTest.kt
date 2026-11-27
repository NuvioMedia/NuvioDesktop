package com.nuvio.app

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.nuvio.app.features.player.ActivePlayerEpisode
import com.nuvio.app.features.player.PlayerStreamsRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLaunch
import com.nuvio.app.features.streams.StreamLaunchStore
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PlayerRoute
import com.nuvio.app.navigation.StreamRoute
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamRouteSyncOnPlayerExitTest {
    @Before
    @After
    fun resetStores() {
        StreamLaunchStore.clear()
        StreamsRepository.clear()
        PlayerStreamsRepository.clearAll()
    }

    @Test
    fun leavesStreamLaunchAndStreamsUnchangedWhenEpisodeDidNotChange() {
        val launchId = StreamLaunchStore.put(
            StreamLaunch(
                profileId = 1,
                type = "series",
                videoId = "tt1234:1:1",
                parentMetaId = "tt1234",
                parentMetaType = "series",
                title = "Example Show",
                seasonNumber = 1,
                episodeNumber = 1,
                episodeTitle = "Pilot",
                manualSelection = true,
            ),
        )
        StreamsRepository.restore(
            type = "series",
            videoId = "tt1234:1:1",
            season = 1,
            episode = 1,
            manualSelection = true,
            state = StreamsUiState(
                groups = listOf(
                    AddonStreamGroup(
                        addonName = "TestAddon",
                        addonId = "test",
                        streams = listOf(
                            StreamItem(
                                name = "1080p",
                                url = "https://example.com/e1.mp4",
                                addonName = "TestAddon",
                                addonId = "test",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val backStack = NavBackStack<NavKey>(
            DetailRoute(type = "series", id = "tt1234", title = "Example Show"),
            StreamRoute(launchId = launchId, title = "Example Show"),
            PlayerRoute(launchId = 99L, title = "Example Show"),
        )
        val navigator = NuvioNavigator(backStack)

        syncUnderlyingStreamRouteOnPlayerExit(
            navController = navigator,
            activeEpisode = ActivePlayerEpisode(
                videoId = "tt1234:1:1",
                seasonNumber = 1,
                episodeNumber = 1,
                episodeTitle = "Pilot",
                episodeThumbnail = null,
                pauseDescription = null,
            ),
        )

        val updated = StreamLaunchStore.get(launchId)!!
        assertEquals(1, updated.episodeNumber)
        assertEquals("tt1234:1:1", updated.videoId)
        assertEquals(1, StreamsRepository.uiState.value.groups.size)
    }

    @Test
    fun updatesUnderlyingStreamLaunchAndClearsStaleStreamsWhenEpisodeAdvances() {
        val launchId = StreamLaunchStore.put(
            StreamLaunch(
                profileId = 1,
                type = "series",
                videoId = "tt1234:1:1",
                parentMetaId = "tt1234",
                parentMetaType = "series",
                title = "Example Show",
                seasonNumber = 1,
                episodeNumber = 1,
                episodeTitle = "Pilot",
                resumePositionMs = 12_000L,
                resumeProgressFraction = 0.2f,
                manualSelection = false,
            ),
        )
        StreamsRepository.restore(
            type = "series",
            videoId = "tt1234:1:1",
            season = 1,
            episode = 1,
            manualSelection = false,
            state = StreamsUiState(
                groups = listOf(
                    AddonStreamGroup(
                        addonName = "TestAddon",
                        addonId = "test",
                        streams = listOf(
                            StreamItem(
                                name = "E1 Stream",
                                url = "https://example.com/e1.mp4",
                                addonName = "TestAddon",
                                addonId = "test",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val backStack = NavBackStack<NavKey>(
            DetailRoute(type = "series", id = "tt1234", title = "Example Show"),
            StreamRoute(launchId = launchId, title = "Example Show"),
            PlayerRoute(launchId = 99L, title = "Example Show"),
        )
        val navigator = NuvioNavigator(backStack)

        syncUnderlyingStreamRouteOnPlayerExit(
            navController = navigator,
            activeEpisode = ActivePlayerEpisode(
                videoId = "tt1234:1:4",
                seasonNumber = 1,
                episodeNumber = 4,
                episodeTitle = "Episode Four",
                episodeThumbnail = "https://example.com/e4.jpg",
                pauseDescription = "Fourth episode overview",
            ),
        )

        val updated = StreamLaunchStore.get(launchId)!!
        assertEquals("tt1234:1:4", updated.videoId)
        assertEquals(1, updated.seasonNumber)
        assertEquals(4, updated.episodeNumber)
        assertEquals("Episode Four", updated.episodeTitle)
        assertEquals("https://example.com/e4.jpg", updated.episodeThumbnail)
        assertEquals("Fourth episode overview", updated.pauseDescription)
        assertNull(updated.resumePositionMs)
        assertNull(updated.resumeProgressFraction)
        assertTrue(updated.manualSelection)
        assertTrue(StreamsRepository.uiState.value.groups.isEmpty())
    }

    @Test
    fun noOpsWhenUnderlyingRouteIsNotStreamRoute() {
        val backStack = NavBackStack<NavKey>(
            DetailRoute(type = "series", id = "tt1234", title = "Example Show"),
            PlayerRoute(launchId = 99L, title = "Example Show"),
        )
        val navigator = NuvioNavigator(backStack)

        syncUnderlyingStreamRouteOnPlayerExit(
            navController = navigator,
            activeEpisode = ActivePlayerEpisode(
                videoId = "tt1234:1:4",
                seasonNumber = 1,
                episodeNumber = 4,
                episodeTitle = "Episode Four",
                episodeThumbnail = null,
                pauseDescription = null,
            ),
        )

        assertTrue(StreamLaunchStore.launchesState.value.isEmpty())
    }
}
