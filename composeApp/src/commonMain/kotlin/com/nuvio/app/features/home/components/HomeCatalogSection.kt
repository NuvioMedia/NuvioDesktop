package com.nuvio.app.features.home.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor
import com.nuvio.app.core.ui.AnimatedArtworkMode
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.core.ui.applyDesktopCardPrefetchOptions
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.isDesktop
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun HomeCatalogRowSection(
    section: HomeCatalogSection,
    modifier: Modifier = Modifier,
    entries: List<MetaPreview> = section.items,
    watchedKeys: Set<String> = emptySet(),
    fullyWatchedSeriesKeys: Set<String> = emptySet(),
    sectionPadding: Dp? = null,
    onViewAllClick: (() -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
) {
    if (sectionPadding != null) {
        HomeCatalogRowSectionContent(
            section = section,
            entries = entries,
            watchedKeys = watchedKeys,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            modifier = modifier.fillMaxWidth(),
            sectionPadding = sectionPadding,
            onViewAllClick = onViewAllClick,
            onPosterClick = onPosterClick,
            onPosterLongClick = onPosterLongClick,
        )
    } else {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            HomeCatalogRowSectionContent(
                section = section,
                entries = entries,
                watchedKeys = watchedKeys,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                modifier = Modifier.fillMaxWidth(),
                sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value),
                onViewAllClick = onViewAllClick,
                onPosterClick = onPosterClick,
                onPosterLongClick = onPosterLongClick,
            )
        }
    }
}

@Composable
private fun HomeCatalogRowSectionContent(
    section: HomeCatalogSection,
    entries: List<MetaPreview>,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    modifier: Modifier,
    sectionPadding: Dp,
    onViewAllClick: (() -> Unit)?,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val rowState = rememberLazyListState()
    if (isDesktop && entries.isNotEmpty()) {
        val platformContext = LocalPlatformContext.current
        val isLandscapeMode = posterCardStyle.catalogLandscapeModeEnabled
        val alwaysShowLandscapeClearlogo = posterCardStyle.alwaysShowLandscapeClearlogo
        val animatedPrefetch = posterCardStyle.animatedArtworkMode != AnimatedArtworkMode.Off
        LaunchedEffect(rowState, section.key, entries, isLandscapeMode, alwaysShowLandscapeClearlogo, animatedPrefetch) {
            // Give fast vertical flings a brief settle window before enqueuing off-screen row decodes.
            delay(DesktopRowPrefetchSettleDelayMs)
            snapshotFlow {
                rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            }
                .distinctUntilChanged()
                .collect { lastVisible ->
                    if (lastVisible == null) return@collect
                    prefetchDesktopPosterItems(
                        platformContext = platformContext,
                        items = entries,
                        startIndex = lastVisible + 1,
                        endIndexExclusive = lastVisible + DesktopRowPrefetchAheadCount + 1,
                        isLandscapeMode = isLandscapeMode,
                        alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo,
                        animated = animatedPrefetch,
                    )
                }
        }
    }

    NuvioShelfSection(
        title = section.title,
        entries = entries,
        modifier = modifier,
        headerHorizontalPadding = sectionPadding,
        rowContentPadding = PaddingValues(horizontal = sectionPadding),
        onViewAllClick = onViewAllClick,
        onTitleClick = onViewAllClick?.takeIf { isDesktop },
        viewAllPillSize = NuvioViewAllPillSize.Compact,
        key = { item -> item.stableKey() },
        state = rowState,
    ) { item ->
        val itemOnClick = remember(onPosterClick, item) { onPosterClick?.let { cb -> { cb(item) } } }
        val itemOnLongClick = remember(onPosterLongClick, item) { onPosterLongClick?.let { cb -> { cb(item) } } }
        HomePosterCard(
            item = item,
            useLandscapeBackdropMode = posterCardStyle.catalogLandscapeModeEnabled,
            isWatched = WatchingState.isPosterWatched(
                watchedKeys = watchedKeys,
                item = item,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            ),
            onClick = itemOnClick,
            onLongClick = itemOnLongClick,
        )
    }
}

private const val DesktopRowPrefetchAheadCount = 6
private const val DesktopRowPrefetchSettleDelayMs = 60L
private const val DesktopPrefetchedKeyCapacity = 200

// Bounded LRU smaller than the ~370-poster memory cache so evicted posters can be re-prefetched
// cleanly when scrolling back through large catalogs.
private val DesktopPrefetchedKeysLock = SynchronizedObject()
private val DesktopPrefetchedKeys = LinkedHashSet<String>(DesktopPrefetchedKeyCapacity)

internal fun prefetchDesktopPosterItems(
    platformContext: PlatformContext,
    items: List<MetaPreview>,
    startIndex: Int,
    endIndexExclusive: Int,
    isLandscapeMode: Boolean,
    alwaysShowLandscapeClearlogo: Boolean,
    animated: Boolean,
) {
    if (!isDesktop || items.isEmpty()) return
    val start = startIndex.coerceIn(0, items.size)
    val end = endIndexExclusive.coerceIn(start, items.size)
    if (start >= end) return

    val imageLoader = SingletonImageLoader.get(platformContext)
    for (index in start until end) {
        val item = items[index]
        val effectiveLandscapePoster = if (alwaysShowLandscapeClearlogo) null else item.landscapePoster
        val imageUrl = if (isLandscapeMode) {
            effectiveLandscapePoster ?: item.banner ?: item.poster
        } else {
            item.poster
        } ?: continue
        val cacheTrackingKey = if (animated) "$imageUrl#anim" else imageUrl
        if (!recordDesktopPrefetchedKey(cacheTrackingKey)) continue
        val fallbackUrl = if (isLandscapeMode && !effectiveLandscapePoster.isNullOrBlank()) {
            item.banner ?: item.rawPosterUrl
        } else {
            item.rawPosterUrl
        }
        val hasFallback = !fallbackUrl.isNullOrBlank() && fallbackUrl != imageUrl
        val request = ImageRequest.Builder(platformContext)
            .data(imageUrl)
            .applyDesktopCardPrefetchOptions(animated = animated)
            .apply {
                if (hasFallback) {
                    memoryCacheKeyExtra(CustomPosterFallbackInterceptor.FALLBACK_URL_KEY, fallbackUrl!!)
                }
            }
            .build()
        imageLoader.enqueue(request)
    }
}

private fun recordDesktopPrefetchedKey(key: String): Boolean = synchronized(DesktopPrefetchedKeysLock) {
    if (DesktopPrefetchedKeys.remove(key)) {
        DesktopPrefetchedKeys.add(key)
        return@synchronized false
    }
    DesktopPrefetchedKeys.add(key)
    if (DesktopPrefetchedKeys.size > DesktopPrefetchedKeyCapacity) {
        val oldest = DesktopPrefetchedKeys.iterator().next()
        DesktopPrefetchedKeys.remove(oldest)
    }
    return@synchronized true
}
