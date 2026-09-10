package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerNowPlayingInfo
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.freedesktop.dbus.DBusPath

class MprisBridgeTest {
    @Test
    fun setPositionIgnoresAStaleTrackId() {
        val commands = mutableListOf<MprisCommand>()
        val player = MprisBridge.MprisObject(commands::add)
        player.update(
            PlayerNowPlayingInfo(itemId = "episode-1", title = "Episode"),
            PlayerPlaybackSnapshot(durationMs = 10_000L),
        )
        val trackId = player.getMetadata().getValue("mpris:trackid").value.toString()

        player.setPosition(DBusPath("/com/nuvio/app/mpris/track/stale"), 3_000_000L)

        assertTrue(commands.isEmpty())
        player.setControllerAvailable(true)
        player.setPosition(DBusPath(trackId), 3_000_000L)
        assertEquals(listOf<MprisCommand>(MprisCommand.SetPosition(3_000_000L)), commands)
    }

    @Test
    fun trackIdChangesWithTheCurrentItem() {
        val player = MprisBridge.MprisObject { }
        player.update(PlayerNowPlayingInfo(itemId = "episode-1", title = "Episode 1"), PlayerPlaybackSnapshot())
        val first = player.getMetadata().getValue("mpris:trackid").value.toString()

        player.update(PlayerNowPlayingInfo(itemId = "episode-2", title = "Episode 2"), PlayerPlaybackSnapshot())
        val second = player.getMetadata().getValue("mpris:trackid").value.toString()

        assertTrue(first.startsWith("/com/nuvio/app/mpris/track/"))
        assertTrue(first != second)
    }

    @Test
    fun trackIdStaysStableWhenDisplayMetadataChangesForTheSameItem() {
        val player = MprisBridge.MprisObject { }
        player.update(
            PlayerNowPlayingInfo(itemId = "episode-1", title = "Episode", artworkUrl = "old.jpg"),
            PlayerPlaybackSnapshot(),
        )
        val originalTrackId = player.getMetadata().getValue("mpris:trackid").value.toString()

        player.update(
            PlayerNowPlayingInfo(
                itemId = "episode-1",
                title = "Renamed Episode",
                subtitle = "New subtitle",
                artworkUrl = "new.jpg",
            ),
            PlayerPlaybackSnapshot(durationMs = 42_000L),
        )

        assertEquals(originalTrackId, player.getMetadata().getValue("mpris:trackid").value.toString())
    }

    @Test
    fun setPositionRejectsValuesOutsideTheTrack() {
        val commands = mutableListOf<MprisCommand>()
        val player = MprisBridge.MprisObject(commands::add)
        player.setControllerAvailable(true)
        player.update(
            PlayerNowPlayingInfo(itemId = "episode-1", title = "Episode"),
            PlayerPlaybackSnapshot(durationMs = 10_000L),
        )
        val trackId = player.getMetadata().getValue("mpris:trackid").value.toString()

        player.setPosition(DBusPath(trackId), -1L)
        player.setPosition(DBusPath(trackId), 10_000_000L)

        assertTrue(commands.isEmpty())
    }

    @Test
    fun repeatAndShuffleAreReadOnlyDefaults() {
        val player = MprisBridge.MprisObject { }

        assertEquals("None", player.getLoopStatus())
        assertFalse(player.getShuffle())
    }

    @Test
    fun playerIsControllableAsSoonAsItsControllerAttaches() {
        val player = MprisBridge.MprisObject { }

        assertFalse(player.getCanPlay())
        assertTrue(player.getCanControl())
        player.setControllerAvailable(true)

        assertTrue(player.getCanPlay())
        assertTrue(player.getCanPause())
        assertTrue(player.getCanControl())
    }

    @Test
    fun updateReportsRateChangesToMprisClients() {
        val player = MprisBridge.MprisObject { }
        player.update(
            PlayerNowPlayingInfo(itemId = "episode-1", title = "Episode"),
            PlayerPlaybackSnapshot(playbackSpeed = 1f),
        )

        val changed = player.update(
            PlayerNowPlayingInfo(itemId = "episode-1", title = "Episode"),
            PlayerPlaybackSnapshot(playbackSpeed = 1.5f),
        )

        assertTrue("Rate" in changed)
        assertEquals(1.5, player.getRate())
    }

    @Test
    fun updateReportsCapabilityChangesToMprisClients() {
        val player = MprisBridge.MprisObject { }
        player.setControllerAvailable(true)
        player.setNavigationCapabilities(canGoNext = true, canGoPrevious = true)

        val changed = player.update(
            PlayerNowPlayingInfo(itemId = "episode-1", title = "Episode"),
            PlayerPlaybackSnapshot(durationMs = 10_000L),
        )

        assertTrue("CanGoNext" in changed)
        assertTrue("CanGoPrevious" in changed)
        assertTrue("CanSeek" in changed)
    }

    @Test
    fun volumeKeepsThePlayersBoostRange() {
        val commands = mutableListOf<MprisCommand>()
        val player = MprisBridge.MprisObject(commands::add)

        player.setVolume(1.5)
        player.updateVolumeLevel(1.5)

        assertEquals(listOf<MprisCommand>(MprisCommand.SetVolume(1.5)), commands)
        assertEquals(1.5, player.getVolume())
    }

    @Test
    fun getAllKeepsRootAndPlayerPropertiesSeparate() {
        val player = MprisBridge.MprisObject { }
        player.update(PlayerNowPlayingInfo(itemId = "episode-1", title = "Episode"), PlayerPlaybackSnapshot())

        val root = player.GetAll("org.mpris.MediaPlayer2")
        val playback = player.GetAll("org.mpris.MediaPlayer2.Player")

        assertTrue("Identity" in root)
        assertFalse("PlaybackStatus" in root)
        assertTrue("Metadata" in playback)
        assertFalse("Identity" in playback)
    }
}
