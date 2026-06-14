package com.nuvio.app.features.player.desktop.mpv

import org.openani.mediamp.mpv.MPVHandle

internal fun MPVHandle.getMpvIntProperty(name: String): Int? =
    runCatching {
        val value = getPropertyString(name)
        value.toIntOrNull() ?: value.toDoubleOrNull()?.toInt()
    }.getOrNull()

internal fun MPVHandle.getMpvStringPropertyOrNull(name: String): String? =
    runCatching { getPropertyString(name) }.getOrNull()

internal fun MPVHandle.getMpvStringProperty(name: String): String =
    getMpvStringPropertyOrNull(name).orEmpty()

internal fun MPVHandle.getMpvBooleanProperty(name: String): Boolean =
    runCatching { getPropertyBoolean(name) }.getOrDefault(false)

internal fun MPVHandle.setMpvProperty(name: String, value: Any): Boolean =
    when (value) {
        is Boolean -> setPropertyBoolean(name, value)
        is Double -> setPropertyDouble(name, value)
        is Float -> setPropertyDouble(name, value.toDouble())
        is Int -> setPropertyInt(name, value)
        is Long -> setPropertyInt(name, value.toInt())
        else -> setPropertyString(name, value.toString())
    }
