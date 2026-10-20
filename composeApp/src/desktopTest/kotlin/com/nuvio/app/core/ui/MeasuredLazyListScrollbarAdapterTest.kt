package com.nuvio.app.core.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class MeasuredLazyListScrollbarAdapterTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var state: LazyListState
    private lateinit var adapter: MeasuredLazyListScrollbarAdapter
    private var listWidthPx by mutableStateOf(WideListPx)

    @Test
    fun scrollOffsetFollowsRealScrollDistanceAcrossMixedItemSizes() {
        setContent()
        var scrolled = 0.0

        while (true) {
            val consumed = scroll(StepPx)
            if (consumed == 0f) break
            scrolled += consumed
            compose.runOnIdle { assertEquals(scrolled, adapter.scrollOffset, Tolerance) }
        }

        compose.runOnIdle {
            assertEquals(ExpectedContentPx, adapter.contentSize, Tolerance)
            assertEquals(ExpectedContentPx - ViewportPx, adapter.scrollOffset, Tolerance)
        }
    }

    @Test
    fun contentSizeStaysFixedOnceEveryItemWasMeasured() {
        setContent()
        while (scroll(StepPx) != 0f) Unit
        while (scroll(-StepPx) != 0f) Unit

        while (true) {
            val consumed = scroll(StepPx)
            if (consumed == 0f) break
            compose.runOnIdle { assertEquals(ExpectedContentPx, adapter.contentSize, Tolerance) }
        }
    }

    @Test
    fun snapLandsOnRequestedOffsetForMeasuredItems() {
        setContent()
        while (scroll(StepPx) != 0f) Unit
        while (scroll(-StepPx) != 0f) Unit
        val target = ViewportPx * 3.5

        compose.runOnIdle { runBlocking { adapter.scrollTo(target) } }

        compose.runOnIdle { assertEquals(target, adapter.scrollOffset, 1.0) }
    }

    @Test
    fun resizingTheListDiscardsHeightsMeasuredAtTheOldWidth() {
        // Narrowing the list doubles every item height, the way wrapped text grows on resize.
        setContent { _ -> if (listWidthPx == WideListPx) 100 else 200 }
        while (scroll(StepPx) != 0f) Unit
        while (scroll(-StepPx) != 0f) Unit
        repeat(30) { scroll(StepPx) }

        compose.runOnIdle { listWidthPx = WideListPx / 2 }
        compose.waitForIdle()

        compose.runOnIdle {
            val itemCount = ItemHeightsPx.size
            val first = state.layoutInfo.visibleItemsInfo.first()
            assertEquals(
                itemCount * 200.0 + SpacingPx * (itemCount - 1) + PaddingPx * 2,
                adapter.contentSize,
                Tolerance,
            )
            assertEquals(first.index * (200.0 + SpacingPx) - first.offset, adapter.scrollOffset, Tolerance)
        }
    }

    private fun setContent(itemHeightPx: (Int) -> Int = { index -> ItemHeightsPx[index] }) {
        compose.setContent {
            state = rememberLazyListState()
            adapter = remember(state) { MeasuredLazyListScrollbarAdapter(state) }
            val density = LocalDensity.current
            // The scrollbar reads the adapter on every layout pass, which is how it sees each item.
            Row {
                with(density) {
                    LazyColumn(
                        state = state,
                        modifier = Modifier.size(width = listWidthPx.toDp(), height = ViewportPx.toInt().toDp()),
                        contentPadding = PaddingValues(top = PaddingPx.toDp(), bottom = PaddingPx.toDp()),
                        verticalArrangement = Arrangement.spacedBy(SpacingPx.toDp()),
                    ) {
                        items(ItemHeightsPx.size) { index ->
                            Box(Modifier.fillMaxWidth().height(itemHeightPx(index).toDp()))
                        }
                    }
                }
                VerticalScrollbar(adapter = adapter, modifier = Modifier.fillMaxHeight())
            }
        }
        compose.waitForIdle()
    }

    private fun scroll(deltaPx: Float): Float {
        var consumed = 0f
        compose.runOnIdle { consumed = state.dispatchRawDelta(deltaPx) }
        compose.waitForIdle()
        return consumed
    }

    private companion object {
        // Short section headers followed by tall cards, like the Settings pages in issue #869.
        val ItemHeightsPx = List(12) { index -> if (index % 3 == 0) 24 else 420 }
        const val ViewportPx = 300.0
        const val WideListPx = 200
        const val PaddingPx = 20
        const val SpacingPx = 10
        const val StepPx = 37f
        const val Tolerance = 0.01
        val ExpectedContentPx =
            ItemHeightsPx.sum() + SpacingPx * (ItemHeightsPx.size - 1) + PaddingPx * 2.0
    }
}
