package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.nuvio.app.features.details.MetaDetails

@Composable
internal fun DesktopStripePlaceholder(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                colors = listOf(
                    colorScheme.surfaceVariant.copy(alpha = 0.48f),
                    colorScheme.background,
                    colorScheme.surfaceVariant.copy(alpha = 0.48f),
                ),
            ),
        ),
    )
}

internal fun desktopYearLabel(meta: MetaDetails): String? =
    meta.releaseInfo
        ?.trim()
        ?.takeIf { it.length >= 4 }
        ?.take(4)

internal fun desktopSeasonCountLabel(meta: MetaDetails): String? {
    val seasons = meta.videos.mapNotNull { it.season }.filter { it > 0 }.toSet().size
    if (seasons <= 0) return null
    return if (seasons == 1) "1 Season" else "$seasons Seasons"
}
