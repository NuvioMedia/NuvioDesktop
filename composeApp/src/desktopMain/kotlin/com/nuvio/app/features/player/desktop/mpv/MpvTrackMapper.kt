package com.nuvio.app.features.player.desktop.mpv

import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.SubtitleTrack
import org.openani.mediamp.mpv.MPVHandle

internal fun MPVHandle.audioTracks(): List<AudioTrack> {
    val count = getMpvIntProperty("track-list/count") ?: return emptyList()
    val tracks = mutableListOf<AudioTrack>()
    for (i in 0 until count) {
        if (getMpvStringProperty("track-list/$i/type") != "audio") continue
        val id = getMpvIntProperty("track-list/$i/id") ?: continue
        val title = getMpvStringProperty("track-list/$i/title")
        val lang = getMpvStringProperty("track-list/$i/lang").takeIf { it.isNotBlank() }
        tracks.add(
            AudioTrack(
                index = tracks.size,
                id = id.toString(),
                label = title.ifEmpty { lang ?: "Track $id" },
                language = lang,
                isSelected = getMpvBooleanProperty("track-list/$i/selected"),
            ),
        )
    }
    return tracks
}

internal fun MPVHandle.subtitleTracks(): List<SubtitleTrack> {
    val count = getMpvIntProperty("track-list/count") ?: return emptyList()
    val tracks = mutableListOf<SubtitleTrack>()
    for (i in 0 until count) {
        if (getMpvStringProperty("track-list/$i/type") != "sub") continue
        val id = getMpvIntProperty("track-list/$i/id") ?: continue
        val title = getMpvStringProperty("track-list/$i/title")
        val lang = getMpvStringProperty("track-list/$i/lang").takeIf { it.isNotBlank() }
        val forced = getMpvBooleanProperty("track-list/$i/forced") || title.contains("forced", ignoreCase = true)
        tracks.add(
            SubtitleTrack(
                index = tracks.size,
                id = id.toString(),
                label = title.ifEmpty { lang ?: "Subtitle $id" },
                language = lang,
                isSelected = getMpvBooleanProperty("track-list/$i/selected"),
                isForced = forced,
            ),
        )
    }
    return tracks
}
