package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.size.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect as SkiaRect

// Cap concurrent CPU raster decodes + mipmap builds while leaving 2 cores free for UI/GPU rendering.
@OptIn(ExperimentalCoroutinesApi::class)
private val DesktopSkiaDecodeDispatcher = Dispatchers.IO.limitedParallelism(
    (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 8),
)

/**
 * Background-thread Skia raster decoder that downscales with trilinear mipmap filtering
 * instead of Coil's default [org.jetbrains.skia.SamplingMode.DEFAULT], keeping posters sharp
 * without doing synchronous CPU rescaling on the UI draw thread.
 */
internal class HighQualitySkiaImageDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? = withContext(DesktopSkiaDecodeDispatcher) {
        val bytes = source.source().use { it.readByteArray() }
        if (bytes.isEmpty()) return@withContext null

        val skiaImage = runCatching { SkiaImage.makeFromEncoded(bytes) }.getOrNull()
            ?: return@withContext null
        try {
            val srcWidth = skiaImage.width
            val srcHeight = skiaImage.height
            if (srcWidth <= 0 || srcHeight <= 0) return@withContext null

            val target = DecodeUtils.computeDstSize(
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                targetSize = options.size,
                scale = options.scale,
                maxSize = options.maxBitmapSize,
            )
            var multiplier = DecodeUtils.computeSizeMultiplier(
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                dstWidth = target.first,
                dstHeight = target.second,
                scale = options.scale,
                maxSize = options.maxBitmapSize,
            )
            if (options.precision == Precision.INEXACT) {
                multiplier = multiplier.coerceAtMost(1.0)
            }

            val dstWidth = (multiplier * srcWidth).toInt().coerceAtLeast(1)
            val dstHeight = (multiplier * srcHeight).toInt().coerceAtLeast(1)
            val isDownscaled = dstWidth < srcWidth || dstHeight < srcHeight

            val bitmap = Bitmap()
            try {
                bitmap.allocN32Pixels(dstWidth, dstHeight)
                Canvas(bitmap).use { canvas ->
                    canvas.drawImageRect(
                        image = skiaImage,
                        src = SkiaRect.makeWH(srcWidth.toFloat(), srcHeight.toFloat()),
                        dst = SkiaRect.makeWH(dstWidth.toFloat(), dstHeight.toFloat()),
                        samplingMode = if (isDownscaled) {
                            FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)
                        } else {
                            FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)
                        },
                        paint = null,
                        strict = true,
                    )
                }
                bitmap.setImmutable()
            } catch (t: Throwable) {
                bitmap.close()
                throw t
            }

            DecodeResult(
                image = bitmap.asImage(),
                isSampled = isDownscaled,
            )
        } finally {
            skiaImage.close()
        }
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (isSvgSource(result)) return null
            return HighQualitySkiaImageDecoder(result.source, options)
        }
    }
}

private fun isSvgSource(result: SourceFetchResult): Boolean {
    val mimeType = result.mimeType
    if (mimeType != null) {
        if (mimeType.equals("image/svg+xml", ignoreCase = true)) return true
        if (mimeType.startsWith("image/", ignoreCase = true) && !mimeType.contains("svg", ignoreCase = true)) {
            return false
        }
    }
    val fileName = result.source.fileOrNull()?.name
    if (fileName != null && fileName.endsWith(".svg", ignoreCase = true)) return true
    return runCatching {
        val peek = result.source.source().peek()
        if (!peek.request(1L) || peek.buffer.size <= 0L) return@runCatching false
        val first = peek.buffer[0].toInt() and 0xFF
        // Fast-reject binary raster formats (JPEG 0xFF, PNG 0x89, WebP 'R', GIF 'G', BMP 'B')
        // without allocating a ByteArray or String.
        if (first != '<'.code && first != 0xEF && first != ' '.code &&
            first != '\t'.code && first != '\r'.code && first != '\n'.code
        ) {
            return@runCatching false
        }
        peek.request(64L)
        val probeLen = minOf(64L, peek.buffer.size)
        if (probeLen <= 0L) return@runCatching false
        val probe = peek.readByteArray(probeLen)
            .decodeToString()
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        probe.startsWith("<")
    }.getOrDefault(false)
}
