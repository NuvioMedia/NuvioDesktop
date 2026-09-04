package com.nuvio.app.features.player

import androidx.compose.ui.Modifier

internal typealias PlayerReleaseBeforeBack = (
    onReleased: () -> Unit,
    onReleaseFailed: (String) -> Unit,
) -> Unit
internal typealias PlayerBackRequest = (releaseBeforeBack: PlayerReleaseBeforeBack) -> Unit

internal data class PlayerScreenArgs(
    val profileId: Int,
    val title: String,
    val sourceUrl: String,
    val sourceAudioUrl: String?,
    val sourceHeaders: Map<String, String>,
    val sourceResponseHeaders: Map<String, String>,
    val streamType: String?,
    val providerName: String,
    val streamTitle: String,
    val streamSubtitle: String?,
    val initialBingeGroup: String?,
    val pauseDescription: String?,
    val onBack: PlayerBackRequest,
    val onSystemBackHandlerChanged: (handler: (() -> Unit)?) -> Unit = {},
    val onOpenInExternalPlayer: ((ExternalPlayerPlaybackRequest) -> Unit)?,
    val onOpenExternalUrl: ((String) -> Unit)?,
    val modifier: Modifier,
    val logo: String?,
    val poster: String?,
    val background: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
    val contentType: String?,
    val videoId: String?,
    val parentMetaId: String,
    val parentMetaType: String,
    val providerAddonId: String?,
    val externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList(),
    val torrentInfoHash: String?,
    val torrentFileIdx: Int?,
    val torrentFilename: String?,
    val torrentTrackers: List<String>,
    val initialPositionMs: Long,
    val initialProgressFraction: Float?,
    val contentLanguage: String? = null,
)
