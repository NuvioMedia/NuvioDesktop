package com.nuvio.app.core.format

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal actual fun formatCalendarDate(isoDate: String, localeTag: String, includeYear: Boolean): String {
    val locale = Locale.forLanguageTag(localeTag)
    val pattern = if (includeYear) "d MMMM yyyy" else "d MMMM"
    return DateTimeFormatter.ofPattern(pattern, locale).format(LocalDate.parse(isoDate))
}
