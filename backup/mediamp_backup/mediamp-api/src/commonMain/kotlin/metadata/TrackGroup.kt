/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.metadata

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi

/**
 * A group of tracks (e.g. audio, subtitle) that can be selected.
 */
@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface TrackGroup<T> {
    /**
     * The currently selected track, or `null` if no track is selected.
     */
    public val selected: StateFlow<T?> // TODO: Is it safe to use StateFlow here?

    /**
     * A flow of the available tracks that can be selected.
     */
    public val candidates: Flow<List<T>>

    /**
     * Selects the given [track] from the candidates.
     *
     * @param track The track to select. `null` removes the current selection.
     */
    public fun select(track: T?): Boolean
}

@InternalMediampApi
@Suppress("UNCHECKED_CAST")
public fun <T> emptyTrackGroup(): TrackGroup<T> = EmptyTrackGroup as TrackGroup<T>

@OptIn(InternalForInheritanceMediampApi::class)
private object EmptyTrackGroup : TrackGroup<Nothing> {
    override val selected: StateFlow<Nothing?> = MutableStateFlow(null)
    override val candidates: Flow<List<Nothing>> = emptyFlow()

    override fun select(track: Nothing?): Boolean = false
}

//fun <T> mutableTrackGroupOf(vararg tracks: T): MutableTrackGroup<T> = MutableTrackGroup<T>().apply {
//    candidates.value = tracks.toList()
//}

//public fun <T> trackGroupOf(vararg tracks: T): TrackGroup<T> = MutableTrackGroup<T>(tracks.toList())
