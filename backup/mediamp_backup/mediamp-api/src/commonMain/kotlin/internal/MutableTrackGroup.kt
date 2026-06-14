/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.internal

import kotlinx.coroutines.flow.MutableStateFlow
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.metadata.TrackGroup

@InternalMediampApi
@OptIn(InternalForInheritanceMediampApi::class)
public class MutableTrackGroup<T>(
    initialCandidates: List<T> = emptyList(),
) : TrackGroup<T> {
    override val selected: MutableStateFlow<T?> = MutableStateFlow(null)
    override val candidates: MutableStateFlow<List<T>> = MutableStateFlow(initialCandidates)

    override fun select(track: T?): Boolean {
        if (track == null) {
            selected.value = null
            return true
        }
        if (track !in candidates.value) {
            return false
        }
        selected.value = track
        return true
    }
}
