package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddonSubtitlePresentationTest {
    @Test fun preservesAddonLabelAndFallbackOrder() {
        assertEquals("📄 OSv3 ⏳", addonSubtitleDisplayLabel("📄 OSv3 ⏳", "wrong", "wrong", "Dutch (USS)"))
        assertEquals("⚡AUTO", addonSubtitleDisplayLabel(" ", "⚡AUTO", null, "Dutch (USS)"))
        assertEquals("DL", addonSubtitleDisplayLabel(null, null, "DL", "Dutch (USS)"))
        assertEquals("Dutch (USS)", addonSubtitleDisplayLabel(null, null, null, "Dutch (USS)"))
        assertEquals("Dutch (USS)", addonSubtitleDisplayLabel("\u0000", " ", "", "Dutch (USS)"))
    }
    @Test fun boundsUntrustedLabelsAndKeepsOtherScripts() {
        assertEquals(240, addonSubtitleDisplayLabel("x".repeat(500), null, null, "fallback").length)
        assertEquals("العربية فارسی 日本語", addonSubtitleDisplayLabel("العربية فارسی 日本語", null, null, "fallback"))
        assertEquals("OS", addonSubtitleDisplayLabel("\u202EOS\u202C", null, null, "fallback"))
    }
}
