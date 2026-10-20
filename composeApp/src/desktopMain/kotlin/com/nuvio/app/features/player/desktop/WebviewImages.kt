package com.nuvio.app.features.player.desktop

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.concurrent.thread

internal object WebviewImages {
    private const val SIZE = 112
    private val images = ConcurrentHashMap<String, String>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    fun resolve(url: String, onReady: () -> Unit): String {
        if (!url.startsWith("file:", ignoreCase = true)) return url
        images[url]?.let { return it }
        if (loading.add(url)) {
            thread(isDaemon = true, name = "webview-image") {
                val image = runCatching { encode(url) }.getOrNull()
                if (image != null) images[url] = image
                loading.remove(url)
                if (image != null) onReady()
            }
        }
        return ""
    }

    private fun encode(url: String): String? {
        val source = ImageIO.read(File(URI(url))) ?: return null
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val scaled = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(source, 0, 0, SIZE, SIZE, left, top, left + side, top + side, null)
            dispose()
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(scaled, "png", it) }.toByteArray()
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    }
}
