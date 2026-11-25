package com.nuvio.app.features.player.desktop

import androidx.compose.ui.graphics.Color
import com.nuvio.app.features.player.SubtitleAppearance
import com.nuvio.app.features.player.SubtitleStyleState
import kotlin.test.Test
import kotlin.test.assertEquals

class NativePlayerSubtitleShadowTest {
    // mpv >= 0.39 made sub-shadow-color an alias of sub-back-color, so the shadow
    // color sent in background-box mode becomes the box color.
    @Test
    fun `background box keeps the background color when shadow is enabled`() {
        val style = SubtitleStyleState(
            backgroundColor = Color(0x99FF0000),
            appearance = SubtitleAppearance(shadowEnabled = true),
        )

        assertEquals("#99FF0000", style.toMpvSubtitleShadow().color)
    }

    @Test
    fun `background box keeps the background color when shadow is disabled`() {
        val style = SubtitleStyleState(
            backgroundColor = Color(0x99000000),
            appearance = SubtitleAppearance(shadowEnabled = false),
        )

        assertEquals("#99000000", style.toMpvSubtitleShadow().color)
    }

    @Test
    fun `outline mode still sends a transparent shadow when shadow is disabled`() {
        val style = SubtitleStyleState(
            backgroundColor = Color.Transparent,
            appearance = SubtitleAppearance(shadowEnabled = false),
        )

        assertEquals("#00000000", style.toMpvSubtitleShadow().color)
    }
}
