package com.nuvio.app.features.player.desktop.mpv

import java.net.URI
import java.security.MessageDigest

internal fun String.redactedMediaUrl(): String =
    runCatching {
        val uri = URI(this)
        val host = uri.host ?: ""
        "host=${host.ifBlank { "unknown" }} len=${length} sha256=${sha256Prefix()}"
    }.getOrElse {
        "host=unknown len=${length} sha256=${sha256Prefix()}"
    }

internal fun String.sha256Prefix(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.take(4).joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}
