package com.nuvio.app.features.search

import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectLinkHistoryRepositoryTest {
    private class Fixture {
        val payloads = mutableMapOf<Int, String>()
        var profileId = 1
        var now = 1L
        val history = DirectLinkHistoryRepository(
            loadPayload = { payloads[it] },
            savePayload = { id, payload -> payloads[id] = payload },
            activeProfileId = { profileId },
            nowEpochMs = { now++ },
        )

        fun session(url: String = "https://cdn.example/video.mp4", profileId: Int = this.profileId): WatchProgressPlaybackSession {
            val launch = history.playerLaunch(parseDirectVideoLink(url)!!, profileId)
            return WatchProgressPlaybackSession(
                profileId = profileId,
                contentType = launch.contentType!!,
                parentMetaId = launch.parentMetaId,
                parentMetaType = launch.parentMetaType,
                videoId = launch.videoId!!,
                title = launch.title,
                lastSourceUrl = launch.sourceUrl,
            )
        }

        fun play(session: WatchProgressPlaybackSession, position: Long = 20_000L, duration: Long = 60_000L, ended: Boolean = false) {
            history.recordPlayback(session, PlayerPlaybackSnapshot(
                isLoading = false, positionMs = position, durationMs = duration, isEnded = ended,
            ))
        }
    }

    @Test
    fun `opening or failing to load a link does not create watched history`() {
        val f = Fixture()
        val session = f.session()
        f.history.ensureLoaded()
        f.history.recordPlayback(session, PlayerPlaybackSnapshot())
        assertTrue(f.history.uiState.value.isEmpty())
        assertTrue(f.payloads.isEmpty())
    }

    @Test
    fun `short direct videos save progress and resume with exact signed url and opaque identity`() {
        val f = Fixture()
        val url = "https://cdn.example/My%20Video.mp4?signature=example%2Bvalue"
        val session = f.session(url)
        f.play(session)
        val entry = f.history.uiState.value.single()
        assertEquals("My Video.mp4", entry.title)
        assertEquals(url, entry.lastSourceUrl)
        assertFalse(entry.videoId.contains("cdn.example"))
        val launch = f.history.playerLaunch(parseDirectVideoLink(url)!!, 1)
        assertEquals(url, launch.sourceUrl)
        assertEquals(session.videoId, launch.videoId)
        assertEquals(20_000L, launch.initialPositionMs)
        assertEquals(DirectLinkContentType, launch.contentType)
    }

    @Test
    fun `history survives reload and removal is persisted`() {
        val f = Fixture()
        val session = f.session()
        f.play(session)
        f.history.clearLocalState()
        f.history.ensureLoaded()
        assertEquals(20_000L, f.history.uiState.value.single().lastPositionMs)
        f.history.remove(session.videoId)
        f.history.clearLocalState()
        f.history.ensureLoaded()
        assertTrue(f.history.uiState.value.isEmpty())
    }

    @Test
    fun `profile switching and late flush cannot leak history across profiles`() {
        val f = Fixture()
        val firstSession = f.session()
        f.play(firstSession)
        f.profileId = 2
        f.history.ensureLoaded()
        assertTrue(f.history.uiState.value.isEmpty())
        f.play(firstSession, position = 25_000L)
        assertTrue(f.history.uiState.value.isEmpty())
        f.play(f.session("https://cdn.example/second.mp4"))
        assertEquals("second.mp4", f.history.uiState.value.single().title)
        f.profileId = 1
        f.history.ensureLoaded()
        assertEquals("video.mp4", f.history.uiState.value.single().title)
        assertEquals(25_000L, f.history.uiState.value.single().lastPositionMs)
    }

    @Test
    fun `repeated playback is deduplicated and moves the link to most recent`() {
        val f = Fixture()
        val firstSession = f.session()
        f.play(firstSession)
        f.play(f.session("https://cdn.example/second.mp4"))
        f.play(firstSession, position = 30_000L)
        assertEquals(listOf("video.mp4", "second.mp4"), f.history.uiState.value.map { it.title })
        assertEquals(30_000L, f.history.uiState.value.first().lastPositionMs)
    }

    @Test
    fun `completed links stay in recent history but replay from beginning`() {
        val f = Fixture()
        val session = f.session()
        f.play(session, position = 60_000L, ended = true)
        assertTrue(f.history.uiState.value.single().isEffectivelyCompleted)
        assertFalse(f.history.uiState.value.single().isResumable)
        assertEquals(0L, f.history.playerLaunch(parseDirectVideoLink(session.lastSourceUrl!!)!!, 1).initialPositionMs)
    }

    @Test
    fun `start from beginning overrides resume and new playback updates position`() {
        val f = Fixture()
        val session = f.session()
        f.play(session)
        assertEquals(0L, f.history.playerLaunch(parseDirectVideoLink(session.lastSourceUrl!!)!!, 1, true).initialPositionMs)
        f.play(session, position = 2_000L)
        assertEquals(2_000L, f.history.uiState.value.single().lastPositionMs)
    }

    @Test
    fun `live streams without duration retain resume progress`() {
        val f = Fixture()
        f.play(f.session("https://cdn.example/live.m3u8"), duration = 0L)
        assertTrue(f.history.uiState.value.single().isResumable)
        assertEquals(20_000L, f.history.uiState.value.single().lastPositionMs)
    }

    @Test
    fun `invalid urls and normal catalog sessions cannot enter direct history`() {
        val f = Fixture()
        val session = f.session()
        f.play(session.copy(lastSourceUrl = "https://"))
        f.play(session.copy(contentType = "movie"))
        assertTrue(f.payloads.isEmpty())
    }

    @Test
    fun `history is bounded to thirty newest links`() {
        val f = Fixture()
        repeat(35) { f.play(f.session("https://cdn.example/video$it.mp4")) }
        assertEquals(30, f.history.uiState.value.size)
        assertEquals("video34.mp4", f.history.uiState.value.first().title)
        assertEquals("video5.mp4", f.history.uiState.value.last().title)
    }
}
