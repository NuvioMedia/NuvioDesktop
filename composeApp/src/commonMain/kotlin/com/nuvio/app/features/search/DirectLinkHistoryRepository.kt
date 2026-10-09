package com.nuvio.app.features.search

import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressCodec
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal const val DirectLinkContentType = "direct_link"
private const val DirectLinkHistoryLimit = 30

/** Local, profile-scoped history. Signed playback URLs never enter progress sync. */
internal class DirectLinkHistoryRepository(
    private val loadPayload: (Int) -> String? = SearchHistoryStorage::loadDirectLinkHistory,
    private val savePayload: (Int, String) -> Unit = SearchHistoryStorage::saveDirectLinkHistory,
    private val activeProfileId: () -> Int = { ProfileRepository.activeProfileId },
    private val nowEpochMs: () -> Long = WatchProgressClock::nowEpochMs,
) {
    companion object {
        val shared = DirectLinkHistoryRepository()
    }

    private val lock = SynchronizedObject()
    private var loadedProfileId: Int? = null
    private val _uiState = MutableStateFlow<List<WatchProgressEntry>>(emptyList())
    val uiState = _uiState.asStateFlow()

    fun ensureLoaded() = synchronized(lock) {
        val profileId = activeProfileId()
        if (loadedProfileId != profileId) {
            loadedProfileId = profileId
            _uiState.value = loadEntries(profileId)
        }
    }

    fun clearLocalState() = synchronized(lock) {
        loadedProfileId = null
        _uiState.value = emptyList()
    }

    @OptIn(ExperimentalUuidApi::class)
    fun playerLaunch(link: DirectVideoLink, profileId: Int, startFromBeginning: Boolean = false): PlayerLaunch =
        synchronized(lock) {
            val existing = entriesForProfile(profileId).firstOrNull { it.lastSourceUrl == link.url }
            val id = existing?.videoId ?: "direct-link:${Uuid.random()}"
            PlayerLaunch(
                profileId = profileId,
                title = link.title,
                sourceUrl = link.url,
                streamTitle = link.title,
                providerName = "",
                contentType = DirectLinkContentType,
                videoId = id,
                parentMetaId = id,
                parentMetaType = DirectLinkContentType,
                initialPositionMs = if (startFromBeginning || existing?.isEffectivelyCompleted == true) 0L
                    else existing?.lastPositionMs ?: 0L,
            )
        }

    fun recordPlayback(session: WatchProgressPlaybackSession, snapshot: PlayerPlaybackSnapshot) = synchronized(lock) {
        if (session.contentType != DirectLinkContentType || session.videoId.isBlank()) return@synchronized
        val link = session.lastSourceUrl?.let(::parseDirectVideoLink) ?: return@synchronized
        val positionMs = snapshot.positionMs.coerceAtLeast(0L)
        if (positionMs < 1_000L && !snapshot.isEnded) return@synchronized
        val durationMs = snapshot.durationMs.coerceAtLeast(0L)
        val entry = WatchProgressEntry(
            contentType = DirectLinkContentType,
            parentMetaId = session.videoId,
            parentMetaType = DirectLinkContentType,
            videoId = session.videoId,
            title = link.title,
            lastPositionMs = positionMs,
            durationMs = durationMs,
            lastUpdatedEpochMs = nowEpochMs(),
            lastSourceUrl = link.url,
            isCompleted = snapshot.isEnded || (durationMs > 0L && positionMs.toDouble() / durationMs >= 0.9),
        )
        val updated = (listOf(entry) + entriesForProfile(session.profileId).filterNot {
            it.videoId == entry.videoId || it.lastSourceUrl == entry.lastSourceUrl
        }).take(DirectLinkHistoryLimit)
        savePayload(session.profileId, WatchProgressCodec.encodeEntries(updated))
        if (session.profileId == activeProfileId()) {
            loadedProfileId = session.profileId
            _uiState.value = updated
        }
    }

    fun remove(videoId: String) = synchronized(lock) {
        ensureLoaded()
        val updated = _uiState.value.filterNot { it.videoId == videoId }
        savePayload(activeProfileId(), WatchProgressCodec.encodeEntries(updated))
        _uiState.value = updated
    }

    private fun entriesForProfile(profileId: Int): List<WatchProgressEntry> =
        if (loadedProfileId == profileId) _uiState.value else loadEntries(profileId)

    private fun loadEntries(profileId: Int): List<WatchProgressEntry> =
        WatchProgressCodec.decodeEntries(loadPayload(profileId).orEmpty()).filter {
            it.contentType == DirectLinkContentType && it.videoId.isNotBlank() &&
                it.lastSourceUrl?.let(::parseDirectVideoLink) != null
        }.sortedByDescending { it.lastUpdatedEpochMs }.take(DirectLinkHistoryLimit)
}
