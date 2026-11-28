package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VrrFramePacingTest {
    @Test
    fun `VRR counts as on when the display is capable and in VRR mode`() {
        // What NVAPI reports for a G-SYNC OLED with G-SYNC switched on: possible + in VRR mode.
        assertTrue(NvidiaVrrInfo.fromBits(0x12).isOn)
        assertTrue(NvidiaVrrInfo.fromBits(0x03).isOn)
    }

    @Test
    fun `VRR counts as off when the display is only capable or not capable`() {
        assertFalse(NvidiaVrrInfo.fromBits(0x02).isOn)
        assertFalse(NvidiaVrrInfo.fromBits(0x00).isOn)
        assertFalse(NvidiaVrrInfo.fromBits(0x11).isOn)
    }

    @Test
    fun `frames are paced only in the foreground on a VRR display with the setting on`() {
        assertTrue(shouldPaceFrames(enabled = true, isForeground = true, isVrrDisplay = true))
        assertFalse(shouldPaceFrames(enabled = false, isForeground = true, isVrrDisplay = true))
        assertFalse(shouldPaceFrames(enabled = true, isForeground = false, isVrrDisplay = true))
        assertFalse(shouldPaceFrames(enabled = true, isForeground = true, isVrrDisplay = false))
    }
}
