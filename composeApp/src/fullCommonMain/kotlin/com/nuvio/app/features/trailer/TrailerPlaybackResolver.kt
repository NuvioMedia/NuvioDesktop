package com.nuvio.app.features.trailer

import kotlinx.coroutines.sync.Mutex
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource

actual object TrailerPlaybackResolver {
    private val extractor by lazy { InAppYouTubeExtractor() }
    private val cacheMutex = Mutex()
    private var playbackCache = emptyMap<String, CachedPlaybackSource>()

    actual suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerPlaybackSource? {
        if (youtubeUrl.isBlank()) return null
        cacheMutex.lock()
        try {
            playbackCache = playbackCache.filterValues { entry ->
                entry.cachedAt.elapsedNow() < PlaybackCacheTtl
            }
            playbackCache[youtubeUrl]?.let { cached ->
                TrailerExtractionPlatform.diagnostic(
                    "cache hit ageMs=${cached.cachedAt.elapsedNow().inWholeMilliseconds}",
                )
                return cached.source
            }
        } finally {
            cacheMutex.unlock()
        }

        val source = extractor.extractPlaybackSource(youtubeUrl)
        if (source != null) {
            cacheMutex.lock()
            try {
                playbackCache = playbackCache + (
                    youtubeUrl to CachedPlaybackSource(
                        source = source,
                        cachedAt = TimeSource.Monotonic.markNow(),
                    )
                )
            } finally {
                cacheMutex.unlock()
            }
        }
        return source
    }

    private data class CachedPlaybackSource(
        val source: TrailerPlaybackSource,
        val cachedAt: TimeMark,
    )
}

// googlevideo URLs carry their own `expire` (~6h), so a short TTL only forces
// needless re-extraction - which is what gets the device rate limited.
private val PlaybackCacheTtl = 3.hours
