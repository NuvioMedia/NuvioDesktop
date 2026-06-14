package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerResizeMode

internal data class DesktopPlayerRequest(
    val sessionKey: String,
    val sourceUrl: String,
    val sourceAudioUrl: String?,
    val sourceHeaders: Map<String, String>,
    val sourceResponseHeaders: Map<String, String>,
    val playWhenReady: Boolean,
    val resizeMode: PlayerResizeMode,
    val seekTargetMs: Long = 0L,
)
