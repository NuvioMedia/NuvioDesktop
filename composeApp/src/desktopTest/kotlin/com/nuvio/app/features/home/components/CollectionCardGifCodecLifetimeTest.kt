package com.nuvio.app.features.home.components

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo

class CollectionCardGifCodecLifetimeTest {
    @Test
    fun evictingCachedCodecDoesNotCloseAHolderStillUsedByACard() {
        // Exercise the real private cache without widening the production API.
        val owner = Class.forName("com.nuvio.app.features.home.components.CollectionCardRemoteImage_desktopKt")
        val field = owner.getDeclaredField("gifCodecCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(null) as MutableMap<String, Any?>
        val holderConstructor = Class.forName("com.nuvio.app.features.home.components.GifCodecHolder")
            .declaredConstructors.single().apply { isAccessible = true }
        val retained = mutableListOf<Codec>()
        synchronized(cache) {
            val previous = cache.toMap()
            cache.clear()
            try {
                repeat(16) { index ->
                    val codec = Data.makeFromBytes(Base64.getDecoder().decode(GIF)).use {
                        Codec.makeFromData(it)
                    }
                    retained += codec
                    cache["codec-lifetime-fixture-$index"] = holderConstructor.newInstance(
                        codec, listOf(200L, 200L), 2, 2, 2, 2, false,
                    )
                }
                assertEquals(15, cache.size)
                val first = retained.first()
                assertFalse(first.isClosed, "Cache eviction must not invalidate a card's retained codec")
                Bitmap().use { bitmap ->
                    bitmap.allocPixels(ImageInfo.makeN32Premul(2, 2))
                    first.readPixels(bitmap, 0)
                    first.readPixels(bitmap, 1)
                }
            } finally {
                cache.clear()
                cache.putAll(previous)
                retained.filterNot { it.isClosed }.forEach { it.close() }
            }
        }
    }

    private companion object {
        // Same two-frame red/green GIF used by CollectionCardRemoteImageTest.
        const val GIF = "R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAFAAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBFAABACwAAAAAAgACAIEAgAAAAAAAAAAAAAAIBgABCAQQEAA7"
    }
}
