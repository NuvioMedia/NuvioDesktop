package com.nuvio.app.core.window

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSystemTrayIconTest {
    @Test
    fun `window closes to tray only when enabled and icon is installed`() {
        assertTrue(shouldHideWindowToTray(closeToTrayEnabled = true, trayIconInstalled = true))
        assertFalse(shouldHideWindowToTray(closeToTrayEnabled = false, trayIconInstalled = true))
        assertFalse(shouldHideWindowToTray(closeToTrayEnabled = true, trayIconInstalled = false))
    }
}
