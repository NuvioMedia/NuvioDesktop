package com.nuvio.app.features.player.desktop

import androidx.compose.ui.unit.IntSize
import com.nuvio.app.features.player.PlayerResizeMode
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPlayerPipSizingTest {
    @Test
    fun `uses display dimensions and falls back as a pair`() {
        val properties = mutableMapOf(
            "video-out-params/dw" to "1920",
            "video-out-params/dh" to "804",
            "video-params/dw" to "720",
            "video-params/dh" to "576",
        )
        fun size() = desktopMpvVideoSize { properties[it].orEmpty() }
        assertEquals(IntSize(1920, 804), size())
        properties["video-out-params/dh"] = "0"
        assertEquals(IntSize(720, 576), size())
        properties["video-params/dw"] = ""
        assertEquals(IntSize.Zero, size())
    }

    @Test
    fun `pip fits video without changing inline resize selection`() {
        for (mode in PlayerResizeMode.entries) {
            assertEquals(PlayerResizeMode.Fit, desktopEffectiveResizeMode(mode, true))
            assertEquals(mode, desktopEffectiveResizeMode(mode, false))
        }
    }

    @Test
    fun `window follows media ratio while retaining its right edge`() {
        val bounds = Rectangle(100, 50, 480, 270)
        val wide = desktopPipBoundsForAspectRatio(bounds, 1920f / 804f)
        assertEquals(Rectangle(100, 50, 480, 201), wide)
        val portrait = desktopPipBoundsForAspectRatio(bounds, 9f / 16f)
        assertEquals(Rectangle(100, 50, 480, 853), portrait)
        val veryWide = desktopPipBoundsForAspectRatio(bounds, 3f)
        assertEquals(Rectangle(40, 50, 540, 180), veryWide)
    }
}
