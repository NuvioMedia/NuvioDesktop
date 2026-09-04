package io.github.kdroidfilter.composemediaplayer.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxFrameLayoutTest {
    @Test
    fun `valid padded layout returns total byte size`() {
        assertEquals(32L, checkedFrameBufferSize(width = 2, height = 2, rowBytes = 16))
    }

    @Test
    fun `invalid or undersized layout is rejected`() {
        assertNull(checkedFrameBufferSize(width = 0, height = 2, rowBytes = 16))
        assertNull(checkedFrameBufferSize(width = 2, height = 0, rowBytes = 16))
        assertNull(checkedFrameBufferSize(width = 2, height = 2, rowBytes = 7))
    }

    @Test
    fun `layout larger than JVM byte buffer capacity is rejected`() {
        assertNull(
            checkedFrameBufferSize(
                width = 16_384,
                height = 32_768,
                rowBytes = 65_536,
            ),
        )
    }
}
