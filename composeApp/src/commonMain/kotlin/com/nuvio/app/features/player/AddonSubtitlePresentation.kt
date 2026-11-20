package com.nuvio.app.features.player

// Preserve the addon's track identity without confusing it with its language.
internal fun addonSubtitleDisplayLabel(label: String?, name: String?, title: String?, fallback: String): String =
    listOf(label, name, title).firstNotNullOfOrNull { value ->
        value?.filterNot { it.code in 0..31 || it.code in 127..159 || it.code in 0x202A..0x202E || it.code in 0x2066..0x2069 }
            ?.trim()?.takeIf(String::isNotBlank)?.take(240)
    } ?: fallback

