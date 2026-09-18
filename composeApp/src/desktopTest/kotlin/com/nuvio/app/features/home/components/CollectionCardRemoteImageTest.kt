package com.nuvio.app.features.home.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import com.sun.net.httpserver.HttpServer
import org.junit.Rule
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionCardRemoteImageTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun animatedWebpPlaysOnHoverAndRestoresCoverOnExit() = assertHoverPlayback("focus.webp", WEBP)

    @Test
    fun animatedGifStillPlaysOnHover() = assertHoverPlayback("focus.gif", GIF)

    @Test
    fun animatedImageDoesNotRequireAFileExtension() = assertHoverPlayback("focus", WEBP)

    private fun assertHoverPlayback(path: String, encodedImage: String) {
        val requests = AtomicInteger()
        val cover = ByteArrayOutputStream().apply {
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).apply {
                for (x in 0..1) for (y in 0..1) setRGB(x, y, 0x0000ff)
            }, "png", this)
        }.toByteArray()
        val animation = Base64.getDecoder().decode(encodedImage)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val isCover = exchange.requestURI.path == "/cover.png"
            if (!isCover) requests.incrementAndGet()
            val bytes = if (isCover) cover else animation
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            compose.setContent {
                CollectionCardRemoteImage(
                    imageUrl = "$baseUrl/$path",
                    staticImageUrl = "$baseUrl/cover.png",
                    contentDescription = "Collection preview",
                    modifier = Modifier.size(64.dp).testTag("card"),
                    contentScale = ContentScale.Crop,
                    animateIfPossible = true,
                )
            }
            val card = compose.onNodeWithTag("card")
            fun waitForColor(channel: Int) {
                compose.waitUntil(timeoutMillis = 5_000) {
                    val color = card.captureToImage().toPixelMap()[16, 16]
                    when (channel) {
                        0 -> color.red > 0.8f && color.green < 0.1f
                        1 -> color.green > 0.4f && color.red < 0.1f
                        else -> color.blue > 0.8f && color.red < 0.1f
                    }
                }
            }
            waitForColor(2)
            assertEquals(0, requests.get(), "The focus asset should not load before hover")
            card.performMouseInput { enter(center) }
            waitForColor(0)
            waitForColor(1)
            assertEquals(1, requests.get())
            card.performMouseInput { exit() }
            waitForColor(2)
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        // Two solid 2x2 frames (red, green), 200 ms each, looping indefinitely.
        const val WEBP = "UklGRoQAAABXRUJQVlA4WAoAAAACAAAAAQAAAQAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAAEAAAEAAMgAAAJWUDhMDwAAAC8BQAAABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAAEAAAEAAMgAAABWUDhMDwAAAC8BQAAAB1DAiP4HIqL/AQA="
        const val GIF = "R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAFAAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBFAABACwAAAAAAgACAIEAgAAAAAAAAAAAAAAIBgABCAQQEAA7"
    }
}
