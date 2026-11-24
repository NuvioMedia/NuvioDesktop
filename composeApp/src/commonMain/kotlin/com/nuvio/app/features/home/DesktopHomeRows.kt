package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.mergeCatalogItems

/**
 * Desktop home rows show up to [DESKTOP_HOME_ROW_ITEM_LIMIT] items, which can take several catalog
 * pages. Fetching every page before a row is shown makes the home wait for the slowest chain of
 * requests on each launch, so a row first shows its first page and is completed once the home is
 * visible.
 */
internal const val DESKTOP_HOME_ROW_ITEM_LIMIT = 64
internal const val DESKTOP_HOME_ROW_MAX_PAGES = 4

/** Where a desktop home row continues after the pages loaded so far. */
internal data class DesktopRowContinuation(
    val nextSkip: Int,
    val rawItemCount: Int,
    val pagesFetched: Int,
)

/** How to continue a row after its first page, or null when there is nothing more to load. */
internal fun desktopRowContinuation(
    supportsPagination: Boolean,
    firstPage: CatalogPage,
    itemCount: Int,
): DesktopRowContinuation? {
    val nextSkip = firstPage.nextSkip
    if (!supportsPagination || nextSkip == null || itemCount >= DESKTOP_HOME_ROW_ITEM_LIMIT) return null
    return DesktopRowContinuation(
        nextSkip = nextSkip,
        rawItemCount = firstPage.rawItemCount,
        pagesFetched = 1,
    )
}

/** Appends the later pages of a row, keeping the items already shown first and in order. */
internal fun HomeCatalogSection.extendedWith(
    laterPages: CatalogPage,
    continuation: DesktopRowContinuation,
): HomeCatalogSection =
    copy(
        items = mergeCatalogItems(items, laterPages.items).take(DESKTOP_HOME_ROW_ITEM_LIMIT),
        availableItemCount = continuation.rawItemCount + laterPages.rawItemCount,
        hasMore = laterPages.nextSkip != null,
    )

/**
 * True when [fresh], a newly loaded first page, is the start of this row, which an earlier load
 * already completed. Keeping this row then avoids shrinking it back to one page while the home is
 * reloaded for each addon manifest that arrives during startup.
 */
internal fun HomeCatalogSection.continuesWith(fresh: HomeCatalogSection): Boolean {
    if (fresh.items.isEmpty() || items.size <= fresh.items.size || target != fresh.target) return false
    return items.asSequence().take(fresh.items.size).map(MetaPreview::stableKey).toList() ==
        fresh.items.map(MetaPreview::stableKey)
}
