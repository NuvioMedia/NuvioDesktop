package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.svg.SvgDecoder
import coil3.toBitmap
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

class SvgPosterLoaderTest {
    @Test
    fun posterWithExternalImageRendersThroughImageLoader() = runBlocking {
        val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(20, 20)) }
        Canvas(bitmap).use { it.clear(0xFFFF0000.toInt()) }
        val png = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 100 100"><rect width="100" height="100" fill="#00FF00"/><image href="http://127.0.0.1:$port/logo.png" x="0" y="0" width="100" height="100"/></svg>"""
        server.createContext("/poster.svg") { ex ->
            val body = svg.encodeToByteArray()
            ex.responseHeaders.add("Content-Type", "image/svg+xml")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/logo.png") { ex ->
            ex.responseHeaders.add("Content-Type", "image/png")
            ex.sendResponseHeaders(200, png.size.toLong())
            ex.responseBody.use { it.write(png) }
        }
        server.start()
        try {
            val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
                .components {
                    addPlatformDecoders()
                    add(SvgDecoder.Factory())
                    add(coil3.network.ktor3.KtorNetworkFetcherFactory())
                }
                .build()
            val result = loader.execute(
                ImageRequest.Builder(PlatformContext.INSTANCE)
                    .data("http://127.0.0.1:$port/poster.svg")
                    .size(Size.ORIGINAL)
                    .build(),
            )
            assertTrue(result is SuccessResult, result.toString())
            val out = result.image.toBitmap()
            assertEquals(0xFFFF0000.toInt(), out.getColor(out.width / 2, out.height / 2))
        } finally {
            server.stop(0)
        }
    }
}
