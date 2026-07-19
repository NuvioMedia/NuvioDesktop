package com.nuvio.app.features.profiles

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileHoverColorTest {

    private val extracted = Color(0xFF123456)

    @Test
    fun `built-in avatar ignores extracted colour and uses avatarColorHex`() {
        val profile = NuvioProfile(
            name = "Built-in",
            avatarColorHex = "#E53935",
            avatarId = "avatar-1",
            avatarUrl = null,
        )
        assertEquals(
            parseHexColor("#E53935"),
            resolveProfileHoverColor(profile, extractedColor = extracted),
        )
    }

    @Test
    fun `custom url avatar uses extracted colour when available`() {
        val profile = NuvioProfile(
            name = "Custom",
            avatarColorHex = "#1E88E5",
            avatarUrl = "https://example.test/avatar.png",
        )
        assertEquals(
            extracted,
            resolveProfileHoverColor(profile, extractedColor = extracted),
        )
    }

    @Test
    fun `custom url avatar falls back to avatarColorHex before extraction`() {
        val profile = NuvioProfile(
            name = "Custom",
            avatarColorHex = "#43A047",
            avatarUrl = "https://example.test/avatar.png",
        )
        assertEquals(
            parseHexColor("#43A047"),
            resolveProfileHoverColor(profile, extractedColor = null),
        )
    }

    @Test
    fun `invalid url is treated as non-custom and keeps avatarColorHex`() {
        val profile = NuvioProfile(
            name = "Broken",
            avatarColorHex = "#FB8C00",
            avatarUrl = "not a url",
        )
        assertEquals(
            parseHexColor("#FB8C00"),
            resolveProfileHoverColor(profile, extractedColor = extracted),
        )
    }

    @Test
    fun `null profile uses the default colour`() {
        assertEquals(
            Color(0xFF1E88E5),
            resolveProfileHoverColor(profile = null, extractedColor = extracted),
        )
    }
}
