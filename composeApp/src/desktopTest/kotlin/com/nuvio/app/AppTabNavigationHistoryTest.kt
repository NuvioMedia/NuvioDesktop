package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppTabNavigationHistoryTest {
    @Test
    fun `history restores visited tabs in reverse order`() {
        val history = AppTabNavigationHistory()

        history.recordTransition(AppScreenTab.Home, AppScreenTab.Search)
        history.recordTransition(AppScreenTab.Search, AppScreenTab.Library)

        assertEquals(AppScreenTab.Search, history.popPrevious(AppScreenTab.Library))
        assertEquals(AppScreenTab.Home, history.popPrevious(AppScreenTab.Search))
        assertNull(history.popPrevious(AppScreenTab.Home))
    }

    @Test
    fun `same-tab selections are not added and history can be reset`() {
        val history = AppTabNavigationHistory()

        history.recordTransition(AppScreenTab.Home, AppScreenTab.Home)
        assertNull(history.popPrevious(AppScreenTab.Home))

        history.recordTransition(AppScreenTab.Home, AppScreenTab.Settings)
        history.clear()
        assertNull(history.popPrevious(AppScreenTab.Settings))
    }
}
