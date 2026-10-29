package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import coil3.Image
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import kotlinx.coroutines.runBlocking
import org.junit.Rule

class AnimatedImagePlaybackTest {
    @get:Rule
    val compose = createComposeRule()

    // region Always

    @Test
    fun animatedGifPlaysWhenAlwaysIsRequested() = withServer(Asset("/badge.gif", "image/gif", LOOPING_GIF)) { server ->
        compose.setContent {
            NuvioAsyncImage(
                model = server.url("/badge.gif?v=2"),
                contentDescription = null,
                modifier = Modifier.size(64.dp).testTag("image"),
                imageLoader = gifLoader(),
                desktopAnimation = NuvioDesktopAnimation.Always,
            )
        }

        // Frames are red, green, then blue, 200 ms each.
        compose.waitForColor("image") { r, g, _ -> r > 0.8f && g < 0.1f }
        compose.waitForColor("image") { r, g, _ -> g > 0.8f && r < 0.1f }
        compose.waitForColor("image") { r, _, b -> b > 0.8f && r < 0.1f }
    }

    @Test
    fun animatedWebpPlaysWhenAlwaysIsRequested() = withServer(Asset("/art.webp", "image/webp", ANIMATED_WEBP)) { server ->
        compose.setContent {
            NuvioAsyncImage(
                model = server.url("/art.webp"),
                contentDescription = null,
                modifier = Modifier.size(64.dp).testTag("image"),
                imageLoader = gifLoader(),
                desktopAnimation = NuvioDesktopAnimation.Always,
            )
        }

        // Two solid frames, red then green.
        compose.waitForColor("image") { r, g, _ -> r > 0.8f && g < 0.1f }
        compose.waitForColor("image") { r, g, _ -> g > 0.4f && r < 0.1f }
    }

    @Test
    fun oneShotGifStopsOnItsLastFrame() = withServer(Asset("/once.gif", "image/gif", ONE_SHOT_GIF)) { server ->
        compose.setContent {
            NuvioAsyncImage(
                model = server.url("/once.gif"),
                contentDescription = null,
                modifier = Modifier.size(64.dp).testTag("image"),
                imageLoader = gifLoader(),
                desktopAnimation = NuvioDesktopAnimation.Always,
            )
        }

        compose.waitForColor("image") { r, _, b -> b > 0.8f && r < 0.1f }
        // Long after the last frame it must not have looped back to red.
        Thread.sleep(1_200)
        val color = compose.colorAt("image")
        assertTrue(color.blue > 0.8f && color.red < 0.1f, "A GIF without a loop count should hold its last frame")
    }

    // endregion

    // region Off / On hover

    @Test
    fun gifStaysOnItsFirstFrameWhenAnimationIsOff() = withServer(Asset("/badge.gif", "image/gif", LOOPING_GIF)) { server ->
        compose.setContent {
            NuvioAsyncImage(
                model = server.url("/badge.gif"),
                contentDescription = null,
                modifier = Modifier.size(64.dp).testTag("image"),
                imageLoader = gifLoader(),
            )
        }

        compose.waitForColor("image") { r, g, _ -> r > 0.8f && g < 0.1f }
        Thread.sleep(900)
        val color = compose.colorAt("image")
        assertTrue(color.red > 0.8f && color.green < 0.1f && color.blue < 0.1f, "Expected a still red frame")
    }

    @Test
    fun onHoverPlaysOnlyWhileTheImageIsHovered() = withServer(Asset("/art.gif", "image/gif", LOOPING_GIF)) { server ->
        compose.setContent {
            NuvioAsyncImage(
                model = server.url("/art.gif"),
                contentDescription = null,
                modifier = Modifier.size(64.dp).testTag("image"),
                imageLoader = gifLoader(),
                desktopAnimation = NuvioDesktopAnimation.OnHover,
            )
        }

        // Without the pointer over it, it stays on the first frame.
        compose.waitForColor("image") { r, g, _ -> r > 0.8f && g < 0.1f }
        Thread.sleep(700)
        val idle = compose.colorAt("image")
        assertTrue(idle.red > 0.8f && idle.green < 0.1f && idle.blue < 0.1f, "Expected the first (red) frame before hover")

        val image = compose.onNodeWithTag("image")
        image.performMouseInput { enter(center) }
        compose.waitForColor("image") { r, g, _ -> g > 0.8f && r < 0.1f }
        compose.waitForColor("image") { r, _, b -> b > 0.8f && r < 0.1f }

        // Leaving it returns to the first frame.
        image.performMouseInput { exit() }
        compose.waitForColor("image") { r, g, _ -> r > 0.8f && g < 0.1f }
    }

    // endregion

    // region Static WebP

    @Test
    fun staticWebpIsLeftToTheDefaultDecoder() = withServer(Asset("/poster.webp", "image/webp", staticWebp())) { server ->
        val decoded = AtomicReference<Image?>()
        compose.setContent {
            NuvioAsyncImage(
                model = server.url("/poster.webp"),
                contentDescription = null,
                modifier = Modifier.size(64.dp).testTag("image"),
                imageLoader = gifLoader(),
                desktopAnimation = NuvioDesktopAnimation.Always,
                onSuccess = { decoded.set(it.result.image) },
            )
        }

        compose.waitForColor("image") { r, g, b -> r < 0.1f && g > 0.8f && b < 0.1f }
        assertFalse(decoded.get() is AnimatedSkiaImage, "A static WebP must not be claimed by the animated decoder")
    }

    // endregion

    // region Sharing and the concurrency cap

    @Test
    fun identicalAnimationsShareOnePlayerEvenWhenLoadedSeparately() = withServer(
        Asset("/a.gif", "image/gif", LOOPING_GIF),
        Asset("/b.gif", "image/gif", LOOPING_GIF),
    ) { server ->
        val loader = gifLoader()
        fun load(url: String) = runBlocking {
            val request = ImageRequest.Builder(PlatformContext.INSTANCE)
                .data(url)
                .apply { extras[NuvioAnimatedImageKey] = true }
                .build()
            (loader.execute(request) as SuccessResult).image as AnimatedSkiaImage
        }

        val first = load(server.url("/a.gif"))
        val second = load(server.url("/b.gif"))

        // Different URLs, so Coil decodes twice, but the same frames must be decoded only once.
        assertSame(first.player, second.player)
    }

    @Test
    fun animationsBeyondTheCapStartWhenASlotFrees() {
        val count = MAX_PLAYERS + 2
        val assets = (0 until count).map { Asset("/g$it.gif", "image/gif", distinctGif(it)) }.toTypedArray()
        withServer(*assets) { server ->
            var hidden by mutableStateOf(emptySet<Int>())
            compose.setContent {
                val loader = gifLoader()
                Column {
                    for (i in 0 until count) {
                        if (i !in hidden) {
                            NuvioAsyncImage(
                                model = server.url("/g$i.gif"),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp).testTag("g$i"),
                                imageLoader = loader,
                                desktopAnimation = NuvioDesktopAnimation.Always,
                            )
                        }
                    }
                }
            }

            fun isRed(i: Int) = compose.colorAt("g$i", x = 8, y = 8).let { it.red > 0.8f && it.green < 0.2f }
            fun everNotRed(indices: List<Int>, forMs: Long): Set<Int> {
                val moved = HashSet<Int>()
                val until = System.currentTimeMillis() + forMs
                while (System.currentTimeMillis() < until) {
                    indices.forEach { if (it !in hidden && !isRed(it)) moved += it }
                    compose.waitForIdle()
                    Thread.sleep(40)
                }
                return moved
            }

            // Everything loads red; exactly MAX_PLAYERS of them start playing.
            compose.waitUntil(timeoutMillis = 8_000) { (0 until count).all { isRed(it) } }
            val playing = everNotRed((0 until count).toList(), 2_000)
            assertEquals(MAX_PLAYERS, playing.size, "Only $MAX_PLAYERS animations may play at once")

            // Two playing ones leave the screen; the two that were waiting must take their slots.
            val waiting = (0 until count).filter { it !in playing }
            val leaving = playing.sorted().take(2)
            hidden = leaving.toSet()
            compose.waitForIdle()
            val nowPlaying = everNotRed(waiting, 4_000)
            assertEquals(waiting.toSet(), nowPlaying, "Waiting animations should start once slots free up")
        }
    }

    // endregion

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.colorAt(tag: String, x: Int = 32, y: Int = 32) =
        onNodeWithTag(tag).captureToImage().toPixelMap()[x, y]

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitForColor(
        tag: String,
        isExpected: (red: Float, green: Float, blue: Float) -> Boolean,
    ) {
        waitUntil(timeoutMillis = 8_000) {
            val color = colorAt(tag)
            isExpected(color.red, color.green, color.blue)
        }
    }

    private fun gifLoader(): ImageLoader =
        ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { add(SkiaGifDecoder.Factory()) }
            .build()

    private class Asset(val path: String, val contentType: String, val bytes: ByteArray) {
        constructor(path: String, contentType: String, base64: String) :
            this(path, contentType, Base64.getDecoder().decode(base64))
    }

    private class TestServer(private val server: HttpServer, private val counts: Map<String, AtomicInteger>) {
        fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
        fun requests(path: String) = counts[path]?.get() ?: 0
    }

    private fun withServer(vararg assets: Asset, block: (TestServer) -> Unit) {
        val counts = assets.associate { it.path to AtomicInteger() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val asset = assets.firstOrNull { it.path == path }
            if (asset == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            counts.getValue(asset.path).incrementAndGet()
            exchange.responseHeaders.add("Content-Type", asset.contentType)
            exchange.sendResponseHeaders(200, asset.bytes.size.toLong())
            exchange.responseBody.use { it.write(asset.bytes) }
        }
        server.start()
        try {
            block(TestServer(server, counts))
        } finally {
            server.stop(0)
        }
    }


    /** A one-frame green WebP, encoded with Skia so the test needs no binary fixture. */
    private fun staticWebp(): ByteArray {
        val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(8, 8)) }
        bitmap.erase(0xFF00FF00.toInt())
        val image = org.jetbrains.skia.Image.makeFromBitmap(bitmap)
        return image.encodeToData(EncodedImageFormat.WEBP, 100)!!.bytes
    }

    /** The looping GIF with a comment block added, so every index has different bytes (and a different hash). */
    private fun distinctGif(index: Int): ByteArray {
        val gif = Base64.getDecoder().decode(LOOPING_GIF)
        val comment = "nuvio-test-$index".toByteArray()
        val extension = byteArrayOf(0x21, 0xFE.toByte(), comment.size.toByte()) + comment + byteArrayOf(0x00)
        return gif.copyOfRange(0, gif.size - 1) + extension + gif.last()
    }

    private companion object {
        const val MAX_PLAYERS = 12

        // 8x8 solid-colour frames (red, green, blue), 200 ms each, looping forever.
        const val LOOPING_GIF =
            "R0lGODlhCAAIAPEAAP8AAAD/AAAA////ACH/C05FVFNDQVBFMi4wAwEAAAAh+QQEFAAAACwAAAAACAAIAAACBoSPqcvtXQAh+QQEFAAAACwAAAAACAAIAAACBoyPqcvtXQAh+QQEFAAAACwAAAAACAAIAAACBpSPqcvtXQA7"

        // Same frames without a loop extension: it plays once and stays on the last frame.
        const val ONE_SHOT_GIF =
            "R0lGODlhCAAIAPEAAP8AAAD/AAAA////ACH5BAQUAAAALAAAAAAIAAgAAAIGhI+py+1dACH5BAQUAAAALAAAAAAIAAgAAAIGjI+py+1dACH5BAQUAAAALAAAAAAIAAgAAAIGlI+py+1dADs="

        // Two solid 2x2 frames (red, green), 200 ms each, looping indefinitely.
        const val ANIMATED_WEBP =
            "UklGRoQAAABXRUJQVlA4WAoAAAACAAAAAQAAAQAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAAEAAAEAAMgAAAJWUDhMDwAAAC8BQAAABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAAEAAAEAAMgAAABWUDhMDwAAAC8BQAAAB1DAiP4HIqL/AQA="
    }
}
