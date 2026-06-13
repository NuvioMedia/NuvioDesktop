package com.nuvio.app.features.player

actual object SubtitleCacheProvider {
    actual suspend fun cacheForExternalPlayer(subtitles: List<SubtitleInput>): List<SubtitleInput>? =
        subtitles.ifEmpty { null }
}
