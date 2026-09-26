package com.nuvio.app.core.ui

import com.nuvio.app.AppScreenTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object AppPresenceState {
    private val _current = MutableStateFlow<PresenceSnapshot?>(null)
    val current: StateFlow<PresenceSnapshot?> = _current.asStateFlow()

    fun publish(snapshot: PresenceSnapshot?) {
        _current.value = snapshot
    }
}

internal sealed interface PresenceSnapshot {
    data class Tab(val tab: AppScreenTab) : PresenceSnapshot

    data class Details(val title: String) : PresenceSnapshot

    data class Player(
        val title: String,
        val episodeLabel: String?,
        val posterUrl: String?,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
    ) : PresenceSnapshot
}
