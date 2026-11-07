package com.nuvio.app.features.player

// Only an actual media basename may become an addon hint. Never pass a signed
// query, URL credentials, a fragment, or an opaque token as the filename.
internal fun subtitleFilenameFromPlaybackUrl(url: String?): String? {
    if (url == null || !(url.startsWith("https://", true) || url.startsWith("http://", true))) return null
    val path = url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")
    val segment = path.substringAfterLast('/').takeIf(String::isNotBlank) ?: return null
    val decoded = runCatching {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < segment.length) {
            if (segment[i] == '%') {
                require(i + 2 < segment.length)
                bytes.add(segment.substring(i + 1, i + 3).toInt(16).toByte()); i += 3
            } else {
                val end = segment.indexOf('%', i).takeIf { it >= 0 } ?: segment.length
                bytes.addAll(segment.substring(i, end).encodeToByteArray().toList()); i = end
            }
        }
        bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }.getOrNull() ?: return null
    if (decoded.length > 1024 || decoded.any { it == '/' || it == '\\' || it.code < 32 || it.code in 127..159 }) return null
    return decoded.takeIf { it.substringAfterLast('.', "").lowercase() in setOf("mkv", "mp4", "m4v", "avi", "webm", "mov", "ts", "m2ts") }
}
