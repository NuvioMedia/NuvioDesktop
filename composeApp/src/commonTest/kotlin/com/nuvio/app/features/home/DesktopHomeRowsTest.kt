package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.CatalogTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopHomeRowsTest {
    @Test
    fun `a paginated row with room left continues after its first page`() {
        val continuation = desktopRowContinuation(
            supportsPagination = true,
            firstPage = page(1..20, nextSkip = 20),
            itemCount = 20,
        )

        assertEquals(DesktopRowContinuation(nextSkip = 20, rawItemCount = 20, pagesFetched = 1), continuation)
    }

    @Test
    fun `a row does not continue without pagination, without a next page or when full`() {
        assertNull(desktopRowContinuation(false, page(1..20, nextSkip = 20), itemCount = 20))
        assertNull(desktopRowContinuation(true, page(1..20, nextSkip = null), itemCount = 20))
        assertNull(desktopRowContinuation(true, page(1..100, nextSkip = 100), itemCount = 64))
    }

    @Test
    fun `later pages are appended after the shown items without duplicates`() {
        val row = section(1..20)
        val continuation = DesktopRowContinuation(nextSkip = 20, rawItemCount = 20, pagesFetched = 1)

        val extended = row.extendedWith(page(15..40, nextSkip = 46, rawItemCount = 26), continuation)

        assertEquals(ids(1..40), extended.items.map(MetaPreview::id))
        assertEquals(46, extended.availableItemCount)
        assertTrue(extended.hasMore)
    }

    @Test
    fun `an extended row stops at the desktop row limit and reports the last page`() {
        val row = section(1..20)
        val continuation = DesktopRowContinuation(nextSkip = 20, rawItemCount = 20, pagesFetched = 1)

        val extended = row.extendedWith(page(21..100, nextSkip = null, rawItemCount = 80), continuation)

        assertEquals(DESKTOP_HOME_ROW_ITEM_LIMIT, extended.items.size)
        assertEquals("tt1", extended.items.first().id)
        assertEquals(100, extended.availableItemCount)
        assertFalse(extended.hasMore)
    }

    @Test
    fun `a completed row is kept when a reload brings back its first page`() {
        val completed = section(1..64)

        assertTrue(completed.continuesWith(section(1..20)))
    }

    @Test
    fun `a reload replaces the row when its content changed`() {
        val completed = section(1..64)

        assertFalse(completed.continuesWith(section(2..21)), "different first items")
        assertFalse(completed.continuesWith(section(1..64)), "not longer than the new page")
        assertFalse(completed.continuesWith(section(1..20, catalogId = "other")), "different catalog")
        assertFalse(completed.continuesWith(section(IntRange.EMPTY)), "empty new page")
    }

    private fun ids(range: IntRange) = range.map { "tt$it" }

    private fun items(range: IntRange) = range.map { MetaPreview(id = "tt$it", type = "movie", name = "Movie $it") }

    private fun page(range: IntRange, nextSkip: Int?, rawItemCount: Int = range.count()) =
        CatalogPage(items = items(range), rawItemCount = rawItemCount, nextSkip = nextSkip)

    private fun section(range: IntRange, catalogId: String = "popular") =
        HomeCatalogSection(
            key = "addon:movie:$catalogId",
            title = "Popular",
            subtitle = "Addon",
            addonName = "Addon",
            target = CatalogTarget.Addon(
                manifestUrl = "https://example.com/manifest.json",
                contentType = "movie",
                catalogId = catalogId,
                supportsPagination = true,
            ),
            items = items(range),
            availableItemCount = range.count(),
            hasMore = true,
        )
}
