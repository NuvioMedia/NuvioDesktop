package org.openani.mediamp.core.progress

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import org.openani.mediamp.core.state.SubtitleTrack

@Immutable
class SubtitlePresentation(
    val subtitleTrack: SubtitleTrack,
    val displayName: String,
)

@Stable
val SubtitleTrack.subtitleLanguage: String
    get() = language ?: labels.firstOrNull()?.value ?: internalId

