package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.memory.MemoryCache

private const val ImageMemoryCacheBytes = 256L * 1024 * 1024

internal actual val platformProvidesImageLoader: Boolean = false

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    return memoryCache {
        MemoryCache.Builder()
            .maxSizeBytes(ImageMemoryCacheBytes)
            .build()
    }.components {
        add(SkiaGifDecoder.Factory())
    }
}
