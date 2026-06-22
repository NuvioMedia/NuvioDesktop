package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.isDesktop
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.nuvioShelfHoverOverdraw
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.watching.application.WatchingState

@Composable
fun DetailPosterRailSection(
    title: String,
    items: List<MetaPreview>,
    watchedKeys: Set<String>,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    headerHorizontalPadding: Dp = 0.dp,
    sourceLabel: String? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
) {
    if (items.isEmpty()) return

    val rowHoverInset = if (isDesktop) DetailRailHoverInset else 0.dp
    val rowEdgePadding = headerHorizontalPadding + rowHoverInset

    Column(modifier = modifier.fillMaxWidth()) {
        NuvioShelfSection(
            title = if (showHeader) title else "",
            entries = items,
            rowModifier = Modifier.nuvioShelfHoverOverdraw(rowHoverInset),
            headerHorizontalPadding = headerHorizontalPadding,
            rowContentPadding = PaddingValues(
                start = rowEdgePadding,
                top = rowHoverInset,
                end = rowEdgePadding,
                bottom = rowHoverInset,
            ),
            showHeaderAccent = false,
            key = { item -> item.stableKey() },
        ) { item ->
            HomePosterCard(
                item = item,
                isWatched = WatchingState.isPosterWatched(
                    watchedKeys = watchedKeys,
                    item = item,
                ),
                onClick = onPosterClick?.let { { it(item) } },
                onLongClick = onPosterLongClick?.let { { it(item) } },
            )
        }

        sourceLabel
            ?.takeIf { it.isNotBlank() }
            ?.let { label ->
                Text(
                    text = label,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = headerHorizontalPadding, top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
    }
}

private val DetailRailHoverInset = 20.dp
