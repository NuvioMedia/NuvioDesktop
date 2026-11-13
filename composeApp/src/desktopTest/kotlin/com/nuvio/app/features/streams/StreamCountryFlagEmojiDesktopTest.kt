package com.nuvio.app.features.streams

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamCountryFlagEmojiDesktopTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun streamCardRendersCountryFlagEmojiOnDesktop() {
        val stream = StreamItem(
            addonId = "torrentio",
            name = "\uD83C\uDDE7\uD83C\uDDF7 Dual Áudio",
            description = "⭐ WEB-DL • 📽 x264 • 🔊 5.1",
            addonName = "Torrentio",
            addonLogo = "",
            behaviorHints = StreamBehaviorHints(),
        )

        compose.setContent {
            NuvioTheme {
                StreamCard(
                    stream = stream,
                    enabled = true,
                    appendInstantServiceToDefaultName = false,
                    showFileSizeBadges = false,
                    showAddonLogo = false,
                    badgePlacement = StreamBadgePlacement.BOTTOM,
                    onClick = {},
                    modifier = Modifier.testTag("streamCard"),
                )
            }
        }

        compose.waitForIdle()
        val node = compose.onNodeWithTag("streamCard")
        node.assertExists()

        val bitmap = node.captureToImage()
        val skiaBitmap = bitmap.asSkiaBitmap()

        val pixelBytes = skiaBitmap.readPixels()
        assertNotNull(pixelBytes)

        val colors = mutableMapOf<String, Int>()
        var i = 0
        while (i < pixelBytes.size) {
            val r = pixelBytes[i].toInt() and 0xFF
            val g = pixelBytes[i + 1].toInt() and 0xFF
            val b = pixelBytes[i + 2].toInt() and 0xFF
            if (maxOf(r, g, b) - minOf(r, g, b) > 30) {
                val key = "($r, $g, $b)"
                colors[key] = (colors[key] ?: 0) + 1
            }
            i += 4
        }
        println("Colorful pixels in StreamCard: ${colors.entries.take(20)}")

        val hasGreen = colors.keys.any { key ->
            val parts = key.removeSurrounding("(", ")").split(", ").map { it.toInt() }
            parts[1] > 120 && parts[0] < 120 && parts[2] < 120
        }
        val hasYellowOrGold = colors.keys.any { key ->
            val parts = key.removeSurrounding("(", ")").split(", ").map { it.toInt() }
            parts[0] > 150 && parts[1] > 140 && parts[2] < 100
        }

        assertTrue(hasGreen, "Stream card should render green pixels from the Brazil flag emoji")
        assertTrue(hasYellowOrGold, "Stream card should render yellow/gold pixels from the Brazil flag emoji")
    }
}
