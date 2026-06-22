package com.nuvio.app.features.addons

internal fun addonTransportBaseUrl(manifestUrl: String): String =
    manifestUrl.substringBefore("?")
        .removeSuffix("/manifest.json")
        .encodeUnsafeHttpUrlCharacters()

internal fun buildAddonResourceUrl(
    manifestUrl: String,
    resource: String,
    type: String,
    id: String,
    extraPathSegment: String? = null,
): String {
    val encodedId = id.encodeAddonPathSegment()
    val baseUrl = addonTransportBaseUrl(manifestUrl)
    val query = manifestUrl.substringAfter("?", "").let { query ->
        if (query.isBlank()) "" else "?$query"
    }
    val resourceUrl = if (extraPathSegment.isNullOrEmpty()) {
        "$baseUrl/$resource/$type/$encodedId.json"
    } else {
        "$baseUrl/$resource/$type/$encodedId/$extraPathSegment.json"
    }
    return (resourceUrl + query).encodeUnsafeHttpUrlCharacters()
}


internal fun String.encodeAddonPathSegment(): String =
    buildString {
        encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (
                char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '_' ||
                char == '.' ||
                char == '~'
            ) {
                append(char)
            } else {
                append('%')
                append(ADDON_URL_HEX[value shr 4])
                append(ADDON_URL_HEX[value and 0x0F])
            }
        }
    }

private const val ADDON_URL_HEX = "0123456789ABCDEF"

internal fun String.encodeUnsafeHttpUrlCharacters(): String =
    buildString(length) {
        this@encodeUnsafeHttpUrlCharacters.forEach { char ->
            when (char) {
                ' ' -> append("%20")
                '"' -> append("%22")
                '<' -> append("%3C")
                '>' -> append("%3E")
                '\\' -> append("%5C")
                '^' -> append("%5E")
                '`' -> append("%60")
                '{' -> append("%7B")
                '|' -> append("%7C")
                '}' -> append("%7D")
                else -> {
                    if (char.code <= 0x1F || char.code == 0x7F) {
                        char.toString().encodeToByteArray().forEach { byte ->
                            val value = byte.toInt() and 0xFF
                            append('%')
                            append(ADDON_URL_HEX[value shr 4])
                            append(ADDON_URL_HEX[value and 0x0F])
                        }
                    } else {
                        append(char)
                    }
                }
            }
        }
    }
