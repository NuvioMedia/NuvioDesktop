package com.nuvio.app.features.player.desktop.hls

import java.net.URI

/**
 * Rewrites HLS playlists so every URI the player can request goes through the
 * loopback header proxy: variant streams, segments, encryption keys, init
 * sections, alternative renditions and playlist hints.
 *
 * The rewrite is deliberately textual. HLS playlists are line oriented and the
 * only structured values that carry URLs are the `URI="..."` attributes, so a
 * full parser would add risk without adding coverage. Everything that is not a
 * URI is preserved byte for byte, including quoting and line endings.
 */
internal object HlsPlaylistRewriter {
    private val uriAttributeTags = listOf(
        "#EXT-X-KEY",
        "#EXT-X-SESSION-KEY",
        "#EXT-X-MAP",
        "#EXT-X-MEDIA",
        "#EXT-X-I-FRAME-STREAM-INF",
        "#EXT-X-IMAGE-STREAM-INF",
        "#EXT-X-RENDITION-REPORT",
        "#EXT-X-PART",
        "#EXT-X-PRELOAD-HINT",
    )

    fun rewrite(
        playlist: String,
        baseUrl: String,
        mapUri: (String) -> String,
    ): String {
        if (playlist.isEmpty()) return playlist

        val lineSeparator = if (playlist.contains("\r\n")) "\r\n" else "\n"
        return playlist.split("\n")
            .joinToString(lineSeparator) { rawLine ->
                val line = rawLine.removeSuffix("\r")
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> line
                    trimmed.startsWith("#") ->
                        if (uriAttributeTags.any { trimmed.startsWith(it) }) {
                            rewriteUriAttributes(line, baseUrl, mapUri)
                        } else {
                            line
                        }
                    else -> mapUri(resolveUrl(baseUrl, trimmed))
                }
            }
    }

    private fun rewriteUriAttributes(
        line: String,
        baseUrl: String,
        mapUri: (String) -> String,
    ): String {
        val builder = StringBuilder(line.length)
        var index = 0
        var inQuotes = false
        while (index < line.length) {
            val character = line[index]
            if (character == '"') {
                inQuotes = !inQuotes
                builder.append(character)
                index++
                continue
            }
            val atAttributeStart = index == 0 || line[index - 1] == ':' || line[index - 1] == ','
            if (!inQuotes && atAttributeStart && line.regionMatches(index, "URI=", 0, 4, ignoreCase = true)) {
                builder.append(line, index, index + 4)
                val valueStart = index + 4
                if (valueStart < line.length && line[valueStart] == '"') {
                    val closingQuote = line.indexOf('"', valueStart + 1)
                    if (closingQuote < 0) {
                        builder.append(line, valueStart, line.length)
                        return builder.toString()
                    }
                    builder.append('"')
                    builder.append(mapUri(resolveUrl(baseUrl, line.substring(valueStart + 1, closingQuote))))
                    builder.append('"')
                    index = closingQuote + 1
                } else {
                    val end = line.indexOf(',', valueStart).let { if (it < 0) line.length else it }
                    builder.append(mapUri(resolveUrl(baseUrl, line.substring(valueStart, end).trim())))
                    index = end
                }
                continue
            }
            builder.append(character)
            index++
        }
        return builder.toString()
    }

    private fun resolveUrl(baseUrl: String, reference: String): String {
        val trimmed = reference.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.startsWith("data:", ignoreCase = true)) return trimmed
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        if (trimmed.startsWith("//")) {
            val scheme = baseUrl.substringBefore(':', missingDelimiterValue = "")
            return if (scheme.isNotEmpty()) "$scheme:$trimmed" else "https:$trimmed"
        }
        return runCatching { URI(baseUrl).resolve(trimmed).toString() }.getOrElse { trimmed }
    }
}
