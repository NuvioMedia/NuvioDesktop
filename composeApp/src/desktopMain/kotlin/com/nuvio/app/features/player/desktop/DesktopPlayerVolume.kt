package com.nuvio.app.features.player.desktop

internal const val DESKTOP_PLAYER_MAX_VOLUME_LEVEL = 2f

internal fun Float.coerceDesktopPlayerVolumeLevel(): Float =
    coerceIn(0f, DESKTOP_PLAYER_MAX_VOLUME_LEVEL)

internal fun resolveDesktopPlayerVolumeLevel(
    requestedLevel: Float?,
    currentLevel: Float?,
    rememberedLevel: Float,
): Float =
    (requestedLevel ?: currentLevel ?: rememberedLevel).coerceDesktopPlayerVolumeLevel()
