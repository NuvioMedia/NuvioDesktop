package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

private fun solid(color: Int, w: Int, h: Int): Image {
    val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(w, h)) }
    Canvas(bitmap).use { it.clear(color) }
    return Image.makeFromBitmap(bitmap)
}

private const val RED = 0xFFFF0000.toInt()
private const val BLUE = 0xFF0000FF.toInt()

class SvgEmbeddedImageDecoderTest {
    @Test
    fun externalImageIsDrawnOverBackground() = runBlocking {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="320" height="200" viewBox="0 0 320 200">
            <rect x="0" y="0" width="320" height="200" fill="#00FF00"/>
            <image href="https://example.test/a.png" x="0" y="0" width="100" height="100" preserveAspectRatio="xMidYMid meet"/>
            </svg>
        """.trimIndent()

        val bitmap = renderSvgWithImages(svg) { solid(RED, 50, 50) }
        assertNotNull(bitmap)
        val scale = bitmap.width / 320f
        assertEquals(RED, bitmap.getColor((50 * scale).toInt(), (50 * scale).toInt()))
        assertEquals(0xFF00FF00.toInt(), bitmap.getColor((250 * scale).toInt(), (150 * scale).toInt()))
    }

    @Test
    fun meetCentersNonSquareImageInsideBox() = runBlocking {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 200 200">
            <rect width="200" height="200" fill="#FFFFFF"/>
            <image href="x" x="0" y="0" width="200" height="200"/>
            </svg>
        """.trimIndent()

        val bitmap = renderSvgWithImages(svg) { solid(BLUE, 100, 50) }
        assertNotNull(bitmap)
        val scale = bitmap.width / 200f
        assertEquals(BLUE, bitmap.getColor((100 * scale).toInt(), (100 * scale).toInt()))
        assertEquals(0xFFFFFFFF.toInt(), bitmap.getColor((100 * scale).toInt(), (20 * scale).toInt()))
    }

    @Test
    fun failedImageLeavesBackgroundIntact() = runBlocking {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 100 100">
            <rect width="100" height="100" fill="#00FF00"/>
            <image href="x" x="0" y="0" width="100" height="100"/>
            </svg>
        """.trimIndent()

        val bitmap = renderSvgWithImages(svg) { null }
        assertNotNull(bitmap)
        assertEquals(0xFF00FF00.toInt(), bitmap.getColor(bitmap.width / 2, bitmap.height / 2))
    }

    @Test
    fun svgWithoutSizeIsRejected() = runBlocking {
        assertNull(renderSvgWithImages("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>") { null })
    }
}
