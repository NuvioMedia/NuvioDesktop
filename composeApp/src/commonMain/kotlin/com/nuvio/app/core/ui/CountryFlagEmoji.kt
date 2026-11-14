package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.twemoji_country_flags
import org.jetbrains.compose.resources.Font

val LocalCountryFlagFontFamily = staticCompositionLocalOf<FontFamily?> { null }

/**
 * Scans [CharSequence] for Unicode country flag emojis (pairs of Regional Indicator Symbols,
 * or subdivision flag tag sequences such as England/Scotland/Wales), returning their character index ranges.
 */
fun CharSequence.findCountryFlagSpans(): List<IntRange> {
    if (isEmpty()) return emptyList()
    var spans: MutableList<IntRange>? = null
    var i = 0
    while (i < length) {
        // Regional Indicator pair: U+1F1E6..U+1F1FF (\uD83C\uDDE6..\uD83C\uDDFF)
        if (i + 3 < length &&
            this[i] == '\uD83C' && this[i + 1] in '\uDDE6'..'\uDDFF' &&
            this[i + 2] == '\uD83C' && this[i + 3] in '\uDDE6'..'\uDDFF'
        ) {
            if (spans == null) spans = mutableListOf()
            spans.add(i..i + 3)
            i += 4
            continue
        }
        // Subdivision flag: U+1F3F4 (\uD83C\uDFF4) followed by tag letters (\uDB40[\uDC61-\uDC7A]) and cancel tag (\uDB40\uDC7F)
        if (i + 1 < length && this[i] == '\uD83C' && this[i + 1] == '\uDFF4') {
            var j = i + 2
            while (j + 1 < length && this[j] == '\uDB40' && this[j + 1] in '\uDC61'..'\uDC7A') {
                j += 2
            }
            if (j + 1 < length && this[j] == '\uDB40' && this[j + 1] == '\uDC7F') {
                if (spans == null) spans = mutableListOf()
                spans.add(i..j + 1)
                i = j + 2
                continue
            }
        }
        i++
    }
    return spans ?: emptyList()
}

/**
 * Checks whether [CharSequence] contains any Unicode country flag emojis.
 */
fun CharSequence.hasCountryFlagEmoji(): Boolean {
    if (isEmpty()) return false
    var i = 0
    while (i < length) {
        if (i + 3 < length &&
            this[i] == '\uD83C' && this[i + 1] in '\uDDE6'..'\uDDFF' &&
            this[i + 2] == '\uD83C' && this[i + 3] in '\uDDE6'..'\uDDFF'
        ) {
            return true
        }
        if (i + 1 < length && this[i] == '\uD83C' && this[i + 1] == '\uDFF4') {
            var j = i + 2
            while (j + 1 < length && this[j] == '\uDB40' && this[j + 1] in '\uDC61'..'\uDC7A') {
                j += 2
            }
            if (j + 1 < length && this[j] == '\uDB40' && this[j + 1] == '\uDC7F') {
                return true
            }
        }
        i++
    }
    return false
}

/**
 * Converts a string to an [AnnotatedString] where country flag emoji spans
 * are assigned the given [flagFontFamily].
 */
fun String.toCountryFlagAnnotatedString(flagFontFamily: FontFamily?): AnnotatedString {
    if (flagFontFamily == null) return AnnotatedString(this)
    val spans = findCountryFlagSpans()
    if (spans.isEmpty()) return AnnotatedString(this)

    return buildAnnotatedString {
        var lastIdx = 0
        for (range in spans) {
            if (range.first > lastIdx) {
                append(this@toCountryFlagAnnotatedString.substring(lastIdx, range.first))
            }
            withStyle(SpanStyle(fontFamily = flagFontFamily)) {
                append(this@toCountryFlagAnnotatedString.substring(range))
            }
            lastIdx = range.last + 1
        }
        if (lastIdx < this@toCountryFlagAnnotatedString.length) {
            append(this@toCountryFlagAnnotatedString.substring(lastIdx))
        }
    }
}

/**
 * Remember and return the country flag [FontFamily] on desktop, or null on other platforms.
 */
@Composable
fun rememberCountryFlagFontFamily(): FontFamily? {
    if (!isDesktop) return null
    val fromLocal = LocalCountryFlagFontFamily.current
    if (fromLocal != null) return fromLocal
    val font = Font(Res.font.twemoji_country_flags)
    return remember(font) { FontFamily(font) }
}

/**
 * Returns an [AnnotatedString] that applies country flag emoji rendering support on desktop.
 * On non-desktop platforms or when no flags are present, returns a plain [AnnotatedString] without extra styles.
 */
@Composable
fun rememberCountryFlagAnnotatedString(text: String): AnnotatedString {
    if (!isDesktop || !text.hasCountryFlagEmoji()) {
        return remember(text) { AnnotatedString(text) }
    }
    val flagFont = rememberCountryFlagFontFamily()
        ?: return remember(text) { AnnotatedString(text) }
    return remember(text, flagFont) {
        text.toCountryFlagAnnotatedString(flagFont)
    }
}
