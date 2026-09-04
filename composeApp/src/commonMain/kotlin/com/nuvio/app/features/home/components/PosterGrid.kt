package com.nuvio.app.features.home.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.NuvioPosterWatchedOverlay
import com.nuvio.app.core.ui.nuvioCardDepth
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.isDesktop

internal fun posterGridColumnCountForWidth(screenWidth: Dp): Int =
    com.nuvio.app.core.ui.posterGridColumnCountForWidth(screenWidth)

@Composable
internal fun PosterGridRow(
    items: List<MetaPreview>,
    columns: Int,
    modifier: Modifier = Modifier,
    watchedKeys: Set<String> = emptySet(),
    fullyWatchedSeriesKeys: Set<String> = emptySet(),
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items.forEach { item ->
            PosterGridTile(
                item = item,
                cornerRadiusDp = posterCardStyle.cornerRadiusDp,
                hideLabels = posterCardStyle.hideLabelsEnabled,
                modifier = Modifier.weight(1f),
                isWatched = WatchingState.isPosterWatched(
                    watchedKeys = watchedKeys,
                    item = item,
                    fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                ),
                onClick = onPosterClick?.let { { it(item) } },
                onLongClick = onPosterLongClick?.let { { it(item) } },
            )
        }
        repeat(columns - items.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun PosterGridSkeletonRow(
    columns: Int,
    modifier: Modifier = Modifier,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(columns) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(0.68f)
                    .clip(RoundedCornerShape(posterCardStyle.cornerRadiusDp.dp))
                    .background(MaterialTheme.colorScheme.surface),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterGridTile(
    item: MetaPreview,
    cornerRadiusDp: Int,
    hideLabels: Boolean,
    modifier: Modifier = Modifier,
    isWatched: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    HomePosterHoverPreview(
        item = item,
        isWatched = isWatched,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(item.posterShape.posterGridAspectRatio())
                    .clip(RoundedCornerShape(cornerRadiusDp.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .nuvioCardDepth(
                        shape = RoundedCornerShape(cornerRadiusDp.dp),
                        surface = NuvioCardDepthSurface.Posters,
                    )
                    .posterCardClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                        zoomImageUrl = item.poster,
                        zoomCornerRadius = cornerRadiusDp.dp,
                    ),
            ) {
                if (item.poster != null) {
                    if (isDesktop) {
                        NuvioAsyncImage(
                            model = item.poster,
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        AsyncImage(
                            model = item.poster,
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                NuvioPosterWatchedOverlay(isWatched = isWatched)
            }
            if (!hideLabels) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = item.releaseInfo?.let { formatReleaseDateForDisplay(it) }
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

private fun PosterShape.posterGridAspectRatio(): Float =
    when (this) {
        PosterShape.Poster -> 0.68f
        PosterShape.Square -> 1f
        PosterShape.Landscape -> 1.78f
    }
