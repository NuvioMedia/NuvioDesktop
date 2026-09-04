package io.github.kdroidfilter.composemediaplayer.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class NativeLibraryLoaderTest {
    @Test
    fun `equal sized native libraries with different content use different cache files`() {
        val first = contentAddressedNativeFileName("libNativeVideoPlayer.so", byteArrayOf(1, 2, 3, 4))
        val second = contentAddressedNativeFileName("libNativeVideoPlayer.so", byteArrayOf(4, 3, 2, 1))

        assertNotEquals(first, second)
        assertEquals(first, contentAddressedNativeFileName("libNativeVideoPlayer.so", byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `native library path segments reject traversal and separators`() {
        listOf(
            "../libNativeVideoPlayer.so",
            "/tmp/libNativeVideoPlayer.so",
            "nested/libNativeVideoPlayer.so",
            "nested\\libNativeVideoPlayer.so",
            "..",
        ).forEach { unsafeName ->
            assertFailsWith<IllegalArgumentException>(unsafeName) {
                validateNativePathSegment(unsafeName)
            }
        }

        assertEquals("NativeVideoPlayer", validateNativePathSegment("NativeVideoPlayer"))
        assertEquals("libNativeVideoPlayer.so", validateNativePathSegment("libNativeVideoPlayer.so"))
    }
}
