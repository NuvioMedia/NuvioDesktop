/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.features

import kotlinx.coroutines.flow.Flow
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.Chapter
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.TrackGroup

/**
 * An optional feature of the [org.openani.mediamp.MediampPlayer] that allows managing audio tracks.
 */
@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface MediaMetadata : Feature {
    /**
     * The group of audio tracks, if supported. Returns `null` if audio tracks are not supported.
     */
    public val audioTracks: TrackGroup<AudioTrack>?

    /**
     * The group of subtitle tracks, if supported. Returns `null` if subtitles are not supported.
     */
    public val subtitleTracks: TrackGroup<SubtitleTrack>?

    /**
     * The list of chapters, if supported. Returns `null` if chapters are not supported.
     *
     * The flow is guaranteed to emit at least one value if there is a media item being played ([MediampPlayer.mediaData] is not `null`).
     */
    public val chapters: Flow<List<Chapter>>?

    public companion object Key : FeatureKey<MediaMetadata>
}

/**
 * Shortcut to access the [MediaMetadata.audioTracks].
 *
 * This method is stable, meaning that it always return the same instance for the same input ([this]).
 */
public val MediampPlayer.audioTracks
    get() = features[MediaMetadata]?.audioTracks

/**
 * Shortcut to access the [MediaMetadata.subtitleTracks].
 *
 * This method is stable, meaning that it always return the same instance for the same input ([this]).
 */
public val MediampPlayer.subtitleTracks
    get() = features[MediaMetadata]?.subtitleTracks

/**
 * Shortcut to access the [MediaMetadata.chapters].
 *
 * This method is stable, meaning that it always return the same instance for the same input ([this]).
 */
public val MediampPlayer.chapters
    get() = features[MediaMetadata]?.chapters

