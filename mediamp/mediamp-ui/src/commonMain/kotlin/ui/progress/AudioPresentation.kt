package org.openani.mediamp.core.progress

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import org.openani.mediamp.core.state.AudioTrack

@Immutable
class AudioPresentation(
    val audioTrack: AudioTrack,
    val displayName: String,
)

@Stable
val AudioTrack.audioName: String
    get() = name ?: labels.firstOrNull()?.value ?: internalId

