package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPlayerVolumeTest {
    @Test
    fun `volume level supports boost through two hundred percent`() {
        assertEquals(0f, (-0.1f).coerceDesktopPlayerVolumeLevel())
        assertEquals(1f, 1f.coerceDesktopPlayerVolumeLevel())
        assertEquals(1.5f, 1.5f.coerceDesktopPlayerVolumeLevel())
        assertEquals(2f, 2.1f.coerceDesktopPlayerVolumeLevel())
    }

    @Test
    fun `control refresh preserves current boosted or muted level`() {
        assertEquals(
            1.75f,
            resolveDesktopPlayerVolumeLevel(
                requestedLevel = null,
                currentLevel = 1.75f,
                rememberedLevel = 1f,
            ),
        )
        assertEquals(
            0f,
            resolveDesktopPlayerVolumeLevel(
                requestedLevel = null,
                currentLevel = 0f,
                rememberedLevel = 1.75f,
            ),
        )
    }

    @Test
    fun `requested level wins and remembered level is the final fallback`() {
        assertEquals(
            1.25f,
            resolveDesktopPlayerVolumeLevel(
                requestedLevel = 1.25f,
                currentLevel = 1.75f,
                rememberedLevel = 1f,
            ),
        )
        assertEquals(
            1.5f,
            resolveDesktopPlayerVolumeLevel(
                requestedLevel = null,
                currentLevel = null,
                rememberedLevel = 1.5f,
            ),
        )
    }
}
