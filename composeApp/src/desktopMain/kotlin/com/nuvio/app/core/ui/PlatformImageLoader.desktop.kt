package com.nuvio.app.core.ui

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import com.nuvio.app.core.storage.DesktopStorage

private const val ImageMemoryCacheBytes = 256L * 1024 * 1024
private const val ImageDiskCacheBytes = 256L * 1024 * 1024

internal actual val platformProvidesImageLoader: Boolean = false

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    return memoryCache {
        MemoryCache.Builder()
            .maxSizeBytes(ImageMemoryCacheBytes)
            .build()
    }.diskCache {
        DiskCache.Builder()
            .directory(DesktopStorage.cacheDir.resolve("image_cache").toFile())
            .maxSizeBytes(ImageDiskCacheBytes)
            .build()
    }
}

internal actual fun ComponentRegistry.Builder.addPlatformDecoders() {
    add(SkiaGifDecoder.Factory())
    add(SvgEmbeddedImageDecoder.Factory())
}
