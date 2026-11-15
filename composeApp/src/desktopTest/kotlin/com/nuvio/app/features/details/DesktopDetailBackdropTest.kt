package com.nuvio.app.features.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.details.components.DesktopDetailBackdrop
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlin.test.Test
import org.junit.Rule

class DesktopDetailBackdropTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun colorOnlyModesHideArtworkButKeepItsPaletteAvailable() {
        val image = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = java.awt.Color.RED
            fillRect(0, 0, image.width, image.height)
            dispose()
        }
        val artwork = ByteArrayOutputStream().apply { ImageIO.write(image, "png", this) }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/artwork.png") { exchange ->
            exchange.responseHeaders.set("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, artwork.size.toLong())
            exchange.responseBody.use { it.write(artwork) }
        }
        server.start()
        try {
            val artworkUrl = "http://127.0.0.1:${server.address.port}/artwork.png"
            val mode = mutableStateOf(MetaScreenBackgroundMode.Normal)
            val normalColor = Color(0xFF101018)
            val dominantColor = Color(0xFF701018)
            val artworkLoaded = mutableStateOf(false)
            compose.setContent {
                MaterialTheme(colorScheme = darkColorScheme(background = normalColor)) {
                    Box(Modifier.size(400.dp, 300.dp).testTag("backdrop")) {
                        DesktopDetailBackdrop(
                            meta = MetaDetails("test", "movie", "Artwork", background = artworkUrl),
                            viewportHeight = 300.dp,
                            heroTrailerSourceUrl = null,
                            heroTrailerSourceAudioUrl = null,
                            heroTrailerReady = false,
                            heroTrailerPlayWhenReady = false,
                            heroTrailerMuted = true,
                            heroGradientColor = dominantColor.takeIf { mode.value == MetaScreenBackgroundMode.DominantColor },
                            showArtwork = mode.value == MetaScreenBackgroundMode.Cinematic,
                            onBackdropLoaded = { artworkLoaded.value = true },
                            onHeroTrailerReady = {},
                            onHeroTrailerEnded = {},
                            onHeroTrailerError = {},
                        )
                    }
                }
            }
            val backdrop = compose.onNodeWithTag("backdrop")
            fun capture(name: String, expected: (Color) -> Boolean) {
                compose.waitUntil(timeoutMillis = 10_000) {
                    val pixels = backdrop.captureToImage().toPixelMap()
                    expected(pixels[pixels.width * 9 / 10, pixels.height / 2])
                }
                val directory = File("build/reports/detail-background").apply { mkdirs() }
                ImageIO.write(backdrop.captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
            }

            compose.waitUntil(timeoutMillis = 10_000) { artworkLoaded.value }
            capture("normal") { it.red < 0.1f }
            compose.runOnIdle { mode.value = MetaScreenBackgroundMode.DominantColor }
            capture("dominant-color") { it.red in 0.4f..0.5f && it.green < 0.1f }
            compose.runOnIdle { mode.value = MetaScreenBackgroundMode.Cinematic }
            capture("cinematic") { it.red > 0.75f }
        } finally {
            server.stop(0)
        }
    }
}
