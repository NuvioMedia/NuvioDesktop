package com.nuvio.app.features.search

import com.nuvio.app.features.tmdb.ImdbIdMatch

internal data class ImdbIdQuery(val imdbId: String)

internal sealed interface ImdbIdMatchUiState {
    val imdbId: String

    data class Resolving(override val imdbId: String) : ImdbIdMatchUiState
    data class Found(val match: ImdbIdMatch) : ImdbIdMatchUiState {
        override val imdbId: String
            get() = match.imdbId
    }
    data class NotFound(override val imdbId: String) : ImdbIdMatchUiState
}

private val bareImdbId = Regex("^tt\\d{7,10}$", RegexOption.IGNORE_CASE)
private val imdbUrlId = Regex("imdb\\.com/(?:title/)?(tt\\d{7,10})", RegexOption.IGNORE_CASE)

internal fun isImdbIdQuery(query: String): Boolean = parseImdbIdQuery(query) != null

internal fun parseImdbIdQuery(query: String): ImdbIdQuery? {
    val value = query.trim().removeSuffix("/")
    if (value.isEmpty() || value.any { it.isISOControl() }) return null

    if (bareImdbId.containsMatchIn(value)) {
        return ImdbIdQuery(imdbId = value.lowercase())
    }

    val isUrl = value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("imdb.com/", ignoreCase = true) ||
        value.startsWith("www.imdb.com/", ignoreCase = true) ||
        value.startsWith("m.imdb.com/", ignoreCase = true)
    if (!isUrl) return null

    return imdbUrlId.find(value)?.let { match ->
        ImdbIdQuery(imdbId = match.groupValues[1].lowercase())
    }
}
