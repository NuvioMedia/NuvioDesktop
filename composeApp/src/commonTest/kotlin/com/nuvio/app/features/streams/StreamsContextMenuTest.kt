package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamsContextMenuTest {
    @Test
    fun positionsMenuAtPointerWhenItFits() {
        assertEquals(
            expected = 240,
            actual = positionContextMenuAxis(
                pointer = 240,
                popupExtent = 200,
                windowExtent = 1000,
                edgeMargin = 8,
            ),
        )
    }

    @Test
    fun opensMenuBackFromFarEdge() {
        assertEquals(
            expected = 760,
            actual = positionContextMenuAxis(
                pointer = 960,
                popupExtent = 200,
                windowExtent = 1000,
                edgeMargin = 8,
            ),
        )
    }

    @Test
    fun clampsMenuInsideBothEdges() {
        assertEquals(
            expected = 8,
            actual = positionContextMenuAxis(
                pointer = 2,
                popupExtent = 200,
                windowExtent = 1000,
                edgeMargin = 8,
            ),
        )
        assertEquals(
            expected = 8,
            actual = positionContextMenuAxis(
                pointer = 50,
                popupExtent = 1200,
                windowExtent = 1000,
                edgeMargin = 8,
            ),
        )
    }
}
