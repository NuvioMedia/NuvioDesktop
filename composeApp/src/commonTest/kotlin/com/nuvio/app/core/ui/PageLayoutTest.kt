package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PageLayoutTest {
    @Test
    fun `desktop page padding follows shared viewport breakpoints`() {
        assertEquals(16f, desktopPageHorizontalPaddingForWidth(767f).value)
        assertEquals(24f, desktopPageHorizontalPaddingForWidth(768f).value)
        assertEquals(28f, desktopPageHorizontalPaddingForWidth(1024f).value)
        assertEquals(32f, desktopPageHorizontalPaddingForWidth(1440f).value)
    }
}
