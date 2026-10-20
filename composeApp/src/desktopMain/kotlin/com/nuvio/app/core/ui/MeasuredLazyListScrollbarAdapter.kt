package com.nuvio.app.core.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.v2.ScrollbarAdapter
import kotlin.math.abs

/**
 * Compose's LazyList scrollbar adapter sizes every item from the average height of the items on
 * screen, so lists that mix short headers with tall cards resize and jump the thumb whenever a
 * different mix scrolls into view. This adapter remembers each item size once it has been laid
 * out and only estimates items that have never been visible.
 */
internal class MeasuredLazyListScrollbarAdapter(
    private val state: LazyListState,
) : ScrollbarAdapter {
    private var recordedLayoutInfo: LazyListLayoutInfo? = null
    private var recordedCrossAxisSize = -1
    private var itemKeys = arrayOfNulls<Any>(0)
    private var itemSizes = IntArray(0)
    private var measuredSizeSum = 0L
    private var measuredCount = 0

    override val viewportSize: Double
        get() = with(state.layoutInfo) {
            if (orientation == Orientation.Vertical) viewportSize.height else viewportSize.width
        }.toDouble()

    override val contentSize: Double
        get() {
            val layoutInfo = recordVisibleItemSizes()
            val itemCount = layoutInfo.totalItemsCount
            return measuredSizeSum + (itemCount - measuredCount) * estimatedItemSize() +
                layoutInfo.mainAxisItemSpacing.toDouble() * (itemCount - 1).coerceAtLeast(0) +
                layoutInfo.beforeContentPadding + layoutInfo.afterContentPadding
        }

    override val scrollOffset: Double
        get() {
            val layoutInfo = recordVisibleItemSizes()
            val first = layoutInfo.firstScrollingItem() ?: return 0.0
            return sizeOfItemsBefore(first.index) +
                layoutInfo.mainAxisItemSpacing.toDouble() * first.index - first.offset
        }

    override suspend fun scrollTo(scrollOffset: Double) {
        val distance = scrollOffset - this.scrollOffset
        // Scrolling by less than a viewport keeps the move exact even across unmeasured items;
        // larger jumps snap so the list doesn't compose every item in between.
        if (abs(distance) <= viewportSize) {
            state.scrollBy(distance.toFloat())
        } else {
            snapTo(scrollOffset)
        }
    }

    private suspend fun snapTo(scrollOffset: Double) {
        val layoutInfo = recordVisibleItemSizes()
        val itemCount = layoutInfo.totalItemsCount
        if (itemCount == 0) return
        val spacing = layoutInfo.mainAxisItemSpacing
        val estimate = estimatedItemSize()
        var remaining = scrollOffset.coerceIn(0.0, (contentSize - viewportSize).coerceAtLeast(0.0))
        var index = 0
        while (index < itemCount - 1) {
            val step = sizeOf(index, estimate) + spacing
            if (remaining < step) break
            remaining -= step
            index++
        }
        state.scrollToItem(index, remaining.toInt())
    }

    private fun recordVisibleItemSizes(): LazyListLayoutInfo {
        val layoutInfo = state.layoutInfo
        if (layoutInfo === recordedLayoutInfo) return layoutInfo
        recordedLayoutInfo = layoutInfo
        val crossAxisSize = with(layoutInfo.viewportSize) {
            if (layoutInfo.orientation == Orientation.Vertical) width else height
        }
        if (itemSizes.size != layoutInfo.totalItemsCount ||
            crossAxisSize != recordedCrossAxisSize ||
            layoutInfo.visibleItemsInfo.any { item -> item.isReplacementAt(item.index) }
        ) {
            // Items were added, removed, or replaced, or the list was resized so text rewraps;
            // either way earlier sizes no longer describe the current content.
            recordedCrossAxisSize = crossAxisSize
            itemKeys = arrayOfNulls(layoutInfo.totalItemsCount)
            itemSizes = IntArray(layoutInfo.totalItemsCount) { UnmeasuredSize }
            measuredSizeSum = 0L
            measuredCount = 0
        }
        layoutInfo.visibleItemsInfo.forEach { item ->
            if (item.index !in itemSizes.indices) return@forEach
            val previousSize = itemSizes[item.index]
            if (previousSize == UnmeasuredSize) {
                measuredCount++
            } else {
                measuredSizeSum -= previousSize
            }
            measuredSizeSum += item.size
            itemSizes[item.index] = item.size
            itemKeys[item.index] = item.key
        }
        return layoutInfo
    }

    private fun LazyListItemInfo.isReplacementAt(index: Int): Boolean {
        val recordedKey = itemKeys.getOrNull(index) ?: return false
        return recordedKey != key
    }

    private fun estimatedItemSize(): Double =
        if (measuredCount == 0) 0.0 else measuredSizeSum.toDouble() / measuredCount

    private fun sizeOf(index: Int, estimate: Double): Double {
        val size = itemSizes[index]
        return if (size == UnmeasuredSize) estimate else size.toDouble()
    }

    private fun sizeOfItemsBefore(index: Int): Double {
        val estimate = estimatedItemSize()
        var total = 0.0
        for (i in 0 until index.coerceAtMost(itemSizes.size)) {
            total += sizeOf(i, estimate)
        }
        return total
    }

    // A pinned sticky header is reported first even though it doesn't scroll with the content.
    private fun LazyListLayoutInfo.firstScrollingItem(): LazyListItemInfo? {
        val items = visibleItemsInfo
        if (items.size < 2) return items.firstOrNull()
        val first = items[0]
        val second = items[1]
        val firstIsPinned = first.index < second.index - 1 ||
            first.offset + first.size + mainAxisItemSpacing > second.offset
        return if (firstIsPinned) second else first
    }

    private companion object {
        const val UnmeasuredSize = -1
    }
}
