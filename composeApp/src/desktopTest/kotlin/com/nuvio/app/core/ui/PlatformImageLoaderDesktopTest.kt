package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.PlatformContext
import com.nuvio.app.core.storage.DesktopStorage
import okio.Path.Companion.toOkioPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PlatformImageLoaderDesktopTest {
    @Test
    fun configurePlatformImageLoaderEnablesMemoryAndDiskCache() {
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
            .configurePlatformImageLoader()
            .build()

        val memoryCache = loader.memoryCache
        assertNotNull(memoryCache)
        assertEquals(256L * 1024 * 1024, memoryCache.maxSize)

        val diskCache = loader.diskCache
        assertNotNull(diskCache)
        assertEquals(256L * 1024 * 1024, diskCache.maxSize)
        assertEquals(
            DesktopStorage.cacheDir.resolve("image_cache").toFile().toOkioPath(),
            diskCache.directory,
        )
    }
}
