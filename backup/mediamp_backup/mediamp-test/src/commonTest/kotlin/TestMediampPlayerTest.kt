/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.test

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.togglePause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TestMediampPlayerTest {

    @Test
    fun `initial state`(): TestResult = runTest {
        val player = TestMediampPlayer()
        // Initially, the player should be in CREATED state
        assertEquals(PlaybackState.CREATED, player.playbackState.value)

        // No media data yet
        val mediaData = player.mediaData.first()
        assertNull(mediaData)
    }

    @Test
    fun `set media`(): TestResult = runTest {
        val player = TestMediampPlayer()
        // Use UriMediaData for demonstration; this requires no real file
        val data = UriMediaData(
            uri = "file:///fake_video.mp4",
            headers = emptyMap(),
            extraFiles = MediaExtraFiles(),
        )

        // Setting the media data should transition the state to READY
        player.setMediaData(data)
        assertEquals(PlaybackState.READY, player.playbackState.value)

        // mediaData flow should now emit the new MediaData
        val currentMediaData = player.mediaData.first()
        assertNotNull(currentMediaData)
        // Because we used UriMediaData, we can check if it's the same instance
        assertEquals(data, currentMediaData)
    }

    @Test
    fun `resume and pause`(): TestResult = runTest {
        val player = TestMediampPlayer()
        val data = UriMediaData(
            uri = "file:///fake_video.mp4",
            headers = emptyMap(),
            extraFiles = MediaExtraFiles(),
        )

        // Setup media data -> READY
        player.setMediaData(data)
        assertEquals(PlaybackState.READY, player.playbackState.value)

        // Resume -> PLAYING
        player.resume()
        assertEquals(PlaybackState.PLAYING, player.playbackState.value)

        // Pause -> PAUSED
        player.pause()
        assertEquals(PlaybackState.PAUSED, player.playbackState.value)
    }

    @Test
    fun `stop playback`(): TestResult = runTest {
        val player = TestMediampPlayer()
        val data = UriMediaData(
            uri = "file:///fake_video.mp4",
            headers = emptyMap(),
            extraFiles = MediaExtraFiles(),
        )

        player.setMediaData(data)
        assertEquals(PlaybackState.READY, player.playbackState.value)

        // Resume -> PLAYING
        player.resume()
        assertEquals(PlaybackState.PLAYING, player.playbackState.value)

        // Stop playback -> FINISHED
        player.stopPlayback()
        assertEquals(PlaybackState.FINISHED, player.playbackState.value)

        // mediaData flow should now emit `null` because we've released the resource
        val mediaDataAfterStop = player.mediaData.first()
        assertNull(mediaDataAfterStop)
    }

    @Test
    fun `close player`(): TestResult = runTest {
        val player = TestMediampPlayer()
        val data = UriMediaData(
            uri = "file:///fake_video.mp4",
            headers = emptyMap(),
            extraFiles = MediaExtraFiles(),
        )

        // Go to READY -> PLAYING
        player.setMediaData(data)
        player.resume()
        assertEquals(PlaybackState.PLAYING, player.playbackState.value)

        // Close -> DESTROYED
        player.close()
        assertEquals(PlaybackState.DESTROYED, player.playbackState.value)

        // Further calls should have no effect, e.g. calling resume or stopPlayback won't change the state
        player.resume()
        assertEquals(PlaybackState.DESTROYED, player.playbackState.value)
        player.stopPlayback()
        assertEquals(PlaybackState.DESTROYED, player.playbackState.value)
    }

    @Test
    fun `seek and skip`(): TestResult = runTest {
        val player = TestMediampPlayer()
        val data = UriMediaData(
            uri = "file:///fake_video.mp4",
            headers = emptyMap(),
            extraFiles = MediaExtraFiles(),
        )

        player.setMediaData(data)
        player.resume()

        // Check initial dummy position
        assertEquals(0L, player.currentPositionMillis.value)

        // Seek to 20s
        player.seekTo(20000L)
        assertEquals(20000L, player.currentPositionMillis.value)

        // Skip +5s => 25s
        player.skip(5000L)
        assertEquals(25000L, player.currentPositionMillis.value)

        // Skip -10s => 15s
        player.skip(-10000L)
        assertEquals(15000L, player.currentPositionMillis.value)
    }

    @Test
    fun `toggle pause`(): TestResult = runTest {
        val player = TestMediampPlayer()
        player.setMediaData(
            UriMediaData(
                uri = "file:///fake_video.mp4",
                headers = emptyMap(),
                extraFiles = MediaExtraFiles(),
            ),
        )

        // READY -> PLAY
        player.resume()
        assertEquals(PlaybackState.PLAYING, player.playbackState.value)

        // togglePause -> PAUSE
        player.togglePause()
        assertEquals(PlaybackState.PAUSED, player.playbackState.value)

        // togglePause -> PLAYING
        player.togglePause()
        assertEquals(PlaybackState.PLAYING, player.playbackState.value)
    }
}