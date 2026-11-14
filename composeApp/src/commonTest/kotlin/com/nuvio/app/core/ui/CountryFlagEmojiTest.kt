package com.nuvio.app.core.ui

import androidx.compose.ui.text.font.FontFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CountryFlagEmojiTest {

    @Test
    fun testHasCountryFlagEmoji() {
        // Pairs of Regional Indicator Symbols
        assertTrue("\uD83C\uDDE7\uD83C\uDDF7 Dual Áudio".hasCountryFlagEmoji()) // Brazil
        assertTrue("⭐ WEB-DL • \uD83C\uDDFA\uD83C\uDDF8 English • 🔊 5.1".hasCountryFlagEmoji()) // USA
        assertTrue("Movie \uD83C\uDDEC\uD83C\uDDE7".hasCountryFlagEmoji()) // UK
        assertTrue("\uD83C\uDDEF\uD83C\uDDF5 Japanese".hasCountryFlagEmoji()) // Japan

        // Subdivision flags
        assertTrue("\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F England".hasCountryFlagEmoji())

        // Non-flag strings
        assertFalse("BR Dual Áudio".hasCountryFlagEmoji())
        assertFalse("US English".hasCountryFlagEmoji())
        assertFalse("⭐ WEB-DL • 📽 x264 • 🔊 5.1".hasCountryFlagEmoji())
        assertFalse("".hasCountryFlagEmoji())
        assertFalse("\uD83C\uDDE7".hasCountryFlagEmoji()) // lone regional indicator
        assertFalse("\uD83C".hasCountryFlagEmoji())
    }

    @Test
    fun testFindCountryFlagSpans() {
        val text = "[ \uD83C\uDDE7\uD83C\uDDF7 ] Dual Áudio \uD83C\uDDFA\uD83C\uDDF8 & \uD83C\uDDEC\uD83C\uDDE7"
        val spans = text.findCountryFlagSpans()
        assertEquals(3, spans.size)

        assertEquals("\uD83C\uDDE7\uD83C\uDDF7", text.substring(spans[0]))
        assertEquals(2..5, spans[0])

        assertEquals("\uD83C\uDDFA\uD83C\uDDF8", text.substring(spans[1]))
        assertEquals("\uD83C\uDDEC\uD83C\uDDE7", text.substring(spans[2]))
    }

    @Test
    fun testToCountryFlagAnnotatedStringWithFlags() {
        val fakeFont = FontFamily.Default
        val text = "\uD83C\uDDE7\uD83C\uDDF7 Dual Áudio"
        val annotated = text.toCountryFlagAnnotatedString(fakeFont)

        assertEquals(text, annotated.text)
        assertEquals(1, annotated.spanStyles.size)
        assertEquals(0, annotated.spanStyles[0].start)
        assertEquals(4, annotated.spanStyles[0].end)
        assertEquals(fakeFont, annotated.spanStyles[0].item.fontFamily)
    }

    @Test
    fun testToCountryFlagAnnotatedStringWithoutFlags() {
        val fakeFont = FontFamily.Default
        val text = "⭐ WEB-DL • 📽 x264 • 🔊 5.1"
        val annotated = text.toCountryFlagAnnotatedString(fakeFont)

        assertEquals(text, annotated.text)
        assertTrue(annotated.spanStyles.isEmpty())
    }

    @Test
    fun testToCountryFlagAnnotatedStringNullFont() {
        val text = "\uD83C\uDDE7\uD83C\uDDF7 Dual Áudio"
        val annotated = text.toCountryFlagAnnotatedString(null)

        assertEquals(text, annotated.text)
        assertTrue(annotated.spanStyles.isEmpty())
    }
}
