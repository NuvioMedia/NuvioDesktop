package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun StreamInfoOverlay(
    visible: Boolean,
    addonName: String,
    addonLogo: String?,
    streamName: String,
    streamDescription: String?,
    playbackEngine: AndroidPlaybackEngine?,
    serverPlayback: String?,
    mediaInfo: PlayerMediaInfo,
    audioTrack: AudioTrack?,
    subtitleTrack: SubtitleTrack?,
    addonSubtitle: AddonSubtitle?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 44.dp, vertical = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StreamInfoSection(stringResource(Res.string.stream_info_section_source)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!addonLogo.isNullOrBlank()) {
                        AsyncImage(
                            model = addonLogo,
                            contentDescription = addonName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column {
                        Text(
                            text = addonName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (streamName.isNotBlank() && streamName != addonName) {
                            StreamInfoSecondaryText(streamName)
                        }
                    }
                }
                if (!streamDescription.isNullOrBlank()) {
                    StreamInfoSecondaryText(streamDescription, Modifier.padding(top = 2.dp))
                }
                if (playbackEngine != null || serverPlayback != null) {
                    Spacer(Modifier.height(6.dp))
                    StreamInfoRow {
                        StreamInfoItem(stringResource(Res.string.stream_info_player_engine), playbackEngine?.label)
                        StreamInfoItem(stringResource(Res.string.stream_info_server), serverPlayback)
                    }
                }
            }

            if (mediaInfo.videoCodec != null || mediaInfo.videoWidth != null || mediaInfo.videoFrameRate != null) {
                StreamInfoSection(stringResource(Res.string.stream_info_section_video)) {
                    StreamInfoRow {
                        StreamInfoItem(stringResource(Res.string.stream_info_codec), mediaInfo.videoCodec)
                        StreamInfoItem(
                            stringResource(Res.string.stream_info_resolution),
                            if (mediaInfo.videoWidth != null && mediaInfo.videoHeight != null) {
                                formatResolution(mediaInfo.videoWidth, mediaInfo.videoHeight)
                            } else null,
                        )
                        StreamInfoItem(
                            stringResource(Res.string.stream_info_frame_rate),
                            mediaInfo.videoFrameRate?.let { "${formatDecimal(it.toDouble(), 3)} fps" },
                        )
                        StreamInfoItem(
                            stringResource(Res.string.stream_info_bitrate),
                            mediaInfo.videoBitrate?.let(::formatBitrate),
                        )
                    }
                }
            }

            if (mediaInfo.audioCodec != null || mediaInfo.audioChannels != null || audioTrack?.language != null) {
                StreamInfoSection(stringResource(Res.string.stream_info_section_audio)) {
                    StreamInfoRow {
                        StreamInfoItem(stringResource(Res.string.stream_info_codec), mediaInfo.audioCodec)
                        StreamInfoItem(
                            stringResource(Res.string.stream_info_channels),
                            mediaInfo.audioChannels?.let(::formatChannelLayout),
                        )
                        StreamInfoItem(
                            stringResource(Res.string.stream_info_sample_rate),
                            mediaInfo.audioSampleRate?.let { "${formatDecimal(it / 1000.0, 1)} kHz" },
                        )
                        StreamInfoItem(
                            stringResource(Res.string.stream_info_language),
                            audioTrack?.language?.takeIf { it.isNotBlank() }?.let { languageLabelForCode(it) },
                        )
                    }
                }
            }

            if (addonSubtitle != null || subtitleTrack != null) {
                StreamInfoSection(stringResource(Res.string.stream_info_section_subtitle)) {
                    StreamInfoRow {
                        StreamInfoItem(
                            stringResource(Res.string.stream_info_name),
                            addonSubtitle?.display ?: subtitleTrack?.let {
                                localizedTrackDisplayName(it.label, it.language, it.index)
                            },
                        )
                        StreamInfoItem(
                            stringResource(Res.string.stream_info_language),
                            (addonSubtitle?.language ?: subtitleTrack?.language)
                                ?.takeIf { it.isNotBlank() }
                                ?.let { languageLabelForCode(it) },
                        )
                        StreamInfoItem(
                            stringResource(Res.string.stream_info_source),
                            stringResource(
                                if (addonSubtitle != null) {
                                    Res.string.stream_info_subtitle_source_addon
                                } else {
                                    Res.string.stream_info_subtitle_source_embedded
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamInfoSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun StreamInfoRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun StreamInfoItem(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    if (value == null) return
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StreamInfoSecondaryText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.replace("\n", " · "),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private fun formatResolution(width: Int, height: Int): String {
    val maxDimension = maxOf(width, height)
    val label = when {
        maxDimension >= 3600 -> "4K"
        maxDimension >= 2400 -> "1440p"
        maxDimension >= 1800 -> "1080p"
        maxDimension >= 1200 -> "720p"
        maxDimension >= 800 -> "480p"
        else -> "${minOf(width, height)}p"
    }
    return "$width × $height ($label)"
}

private fun formatBitrate(bitsPerSecond: Int): String =
    when {
        bitsPerSecond >= 1_000_000 -> "${formatDecimal(bitsPerSecond / 1_000_000.0, 1)} Mbps"
        bitsPerSecond >= 1_000 -> "${(bitsPerSecond / 1_000.0).roundToInt()} kbps"
        else -> "$bitsPerSecond bps"
    }

private fun formatChannelLayout(channels: Int): String =
    when (channels) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${channels}ch"
    }

private fun formatDecimal(value: Double, decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    return ((value * factor).roundToInt() / factor).toString().removeSuffix(".0")
}

@Composable
internal fun rememberStreamInfoSections(
    addonName: String,
    addonLogo: String?,
    streamName: String,
    streamDescription: String?,
    playbackEngine: AndroidPlaybackEngine?,
    serverPlayback: String?,
    mediaInfo: PlayerMediaInfo,
    audioTrack: AudioTrack?,
    subtitleTrack: SubtitleTrack?,
    addonSubtitle: AddonSubtitle?,
): List<PlayerControlInfoSection> {
    fun items(vararg pairs: Pair<String, String?>) =
        pairs.mapNotNull { (label, value) -> value?.let { PlayerControlInfoItem(label, it) } }

    val source = PlayerControlInfoSection(
        title = stringResource(Res.string.stream_info_section_source),
        logo = addonLogo?.takeIf { it.isNotBlank() },
        name = addonName,
        subtitle = streamName.takeIf { it.isNotBlank() && it != addonName },
        description = streamDescription?.takeIf { it.isNotBlank() }?.replace("\n", " · "),
        items = items(
            stringResource(Res.string.stream_info_player_engine) to playbackEngine?.label,
            stringResource(Res.string.stream_info_server) to serverPlayback,
        ),
    )
    val video = if (mediaInfo.videoCodec != null || mediaInfo.videoWidth != null || mediaInfo.videoFrameRate != null) {
        PlayerControlInfoSection(
            title = stringResource(Res.string.stream_info_section_video),
            items = items(
                stringResource(Res.string.stream_info_codec) to mediaInfo.videoCodec,
                stringResource(Res.string.stream_info_resolution) to
                    if (mediaInfo.videoWidth != null && mediaInfo.videoHeight != null) {
                        formatResolution(mediaInfo.videoWidth, mediaInfo.videoHeight)
                    } else null,
                stringResource(Res.string.stream_info_frame_rate) to
                    mediaInfo.videoFrameRate?.let { "${formatDecimal(it.toDouble(), 3)} fps" },
                stringResource(Res.string.stream_info_bitrate) to mediaInfo.videoBitrate?.let(::formatBitrate),
            ),
        )
    } else null
    val audio = if (mediaInfo.audioCodec != null || mediaInfo.audioChannels != null || audioTrack?.language != null) {
        PlayerControlInfoSection(
            title = stringResource(Res.string.stream_info_section_audio),
            items = items(
                stringResource(Res.string.stream_info_codec) to mediaInfo.audioCodec,
                stringResource(Res.string.stream_info_channels) to mediaInfo.audioChannels?.let(::formatChannelLayout),
                stringResource(Res.string.stream_info_sample_rate) to
                    mediaInfo.audioSampleRate?.let { "${formatDecimal(it / 1000.0, 1)} kHz" },
                stringResource(Res.string.stream_info_language) to
                    audioTrack?.language?.takeIf { it.isNotBlank() }?.let { languageLabelForCode(it) },
            ),
        )
    } else null
    val subtitle = if (addonSubtitle != null || subtitleTrack != null) {
        PlayerControlInfoSection(
            title = stringResource(Res.string.stream_info_section_subtitle),
            items = items(
                stringResource(Res.string.stream_info_name) to (
                    addonSubtitle?.display ?: subtitleTrack?.let {
                        localizedTrackDisplayName(it.label, it.language, it.index)
                    }
                    ),
                stringResource(Res.string.stream_info_language) to
                    (addonSubtitle?.language ?: subtitleTrack?.language)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { languageLabelForCode(it) },
                stringResource(Res.string.stream_info_source) to stringResource(
                    if (addonSubtitle != null) {
                        Res.string.stream_info_subtitle_source_addon
                    } else {
                        Res.string.stream_info_subtitle_source_embedded
                    },
                ),
            ),
        )
    } else null
    return listOfNotNull(source, video, audio, subtitle)
}
