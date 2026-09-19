package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import java.io.File

private fun resolveDesktopCacheDir(): File {
    val userHome = System.getProperty("user.home").orEmpty()
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val baseDir = when {
        os.contains("win") -> System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let { File(it, "Nuvio") }
            ?: File(userHome, ".nuvio")
        os.contains("mac") -> File(userHome, "Library/Caches/com.nuvio.app")
        else -> System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)?.let { File(it, "nuvio") }
            ?: File(userHome, ".cache/nuvio")
    }
    return File(baseDir, "image_cache").apply { mkdirs() }
}

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    val maxMemory = Runtime.getRuntime().maxMemory()
    val memoryCacheBytes = if (maxMemory > 0L) (maxMemory * 0.25).toLong() else 256L * 1024L * 1024L

    return this
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(memoryCacheBytes)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(resolveDesktopCacheDir().toOkioPath())
                .maxSizeBytes(200L * 1024L * 1024L) // 200MB (matching TV & Mobile)
                .build()
        }
        .components {
            add(SkiaGifDecoder.Factory())
        }
}
