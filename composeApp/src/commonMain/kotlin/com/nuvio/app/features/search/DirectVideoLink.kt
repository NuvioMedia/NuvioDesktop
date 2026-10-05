package com.nuvio.app.features.search

import io.ktor.http.Url

internal data class DirectVideoLink(val url: String, val title: String)

private val linkScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

internal fun isVideoLinkQuery(query: String): Boolean {
    val value = query.trim()
    return linkScheme.containsMatchIn(value) ||
        value.startsWith("http:", ignoreCase = true) ||
        value.startsWith("https:", ignoreCase = true)
}

internal fun parseDirectVideoLink(query: String): DirectVideoLink? {
    val value = query.trim()
    if (!value.startsWith("http://", ignoreCase = true) &&
        !value.startsWith("https://", ignoreCase = true)
    ) return null
    if (value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
    val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isBlank()) return null
    return runCatching {
        val parsed = Url(value)
        if (parsed.host.isBlank()) return null
        DirectVideoLink(
            url = value,
            title = parsed.parameters["filename"]?.substringAfterLast('/')?.substringAfterLast('\\')
                ?.takeIf { it.isNotBlank() }
                ?: parsed.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }
                ?: parsed.host,
        )
    }.getOrNull()
}
