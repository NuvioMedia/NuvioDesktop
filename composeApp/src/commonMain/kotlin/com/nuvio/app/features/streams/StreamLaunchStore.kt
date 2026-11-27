package com.nuvio.app.features.streams

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StreamLaunch(
    val profileId: Int,
    val type: String,
    val videoId: String,
    val parentMetaId: String? = null,
    val parentMetaType: String? = null,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val resumePositionMs: Long? = null,
    val resumeProgressFraction: Float? = null,
    val manualSelection: Boolean = false,
    val startFromBeginning: Boolean = false,
)

object StreamLaunchStore {
    private var nextLaunchId = 1L
    private val _launchesState = MutableStateFlow<Map<Long, StreamLaunch>>(emptyMap())
    val launchesState: StateFlow<Map<Long, StreamLaunch>> = _launchesState.asStateFlow()

    fun put(launch: StreamLaunch): Long {
        val launchId = nextLaunchId++
        _launchesState.update { it + (launchId to launch) }
        return launchId
    }

    fun get(launchId: Long): StreamLaunch? = _launchesState.value[launchId]

    fun update(launchId: Long, transform: (StreamLaunch) -> StreamLaunch): StreamLaunch? {
        var updated: StreamLaunch? = null
        _launchesState.update { current ->
            val existing = current[launchId] ?: return@update current
            updated = transform(existing)
            current + (launchId to updated!!)
        }
        return updated
    }

    fun remove(launchId: Long) {
        _launchesState.update { it - launchId }
    }

    fun clear() {
        nextLaunchId = 1L
        _launchesState.value = emptyMap()
    }
}
