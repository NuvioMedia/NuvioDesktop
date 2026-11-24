package com.nuvio.app.core.ui

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade

private const val ImageMemoryCacheBytes = 256L * 1024 * 1024

internal actual val platformProvidesImageLoader: Boolean = false

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    // Disable multi-frame alpha crossfades on desktop so scrolling through poster rows does not
    // continuously composite dozens of transitioning image layers on top of scroll frames.
    return crossfade(false).memoryCache {
        MemoryCache.Builder()
            .maxSizeBytes(ImageMemoryCacheBytes)
            .build()
    }
}

internal actual fun ComponentRegistry.Builder.addPlatformDecoders() {
    add(SkiaGifDecoder.Factory())
    add(SvgEmbeddedImageDecoder.Factory())
    add(HighQualitySkiaImageDecoder.Factory())
}
