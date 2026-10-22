package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.toBitmap
import java.net.URLDecoder
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.svg.SVGDOM

private const val TARGET_WIDTH = 800f
private const val MAX_SCALE = 4f

private val imageTag = Regex("<image\\b[^>]*>", RegexOption.IGNORE_CASE)
private val rootTag = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)
private val separators = Regex("[\\s,]+")

private class EmbeddedImage(
    val href: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val preserveAspectRatio: String,
)

internal suspend fun renderSvgWithImages(svg: String, loadImage: suspend (String) -> Image?): Bitmap? {
    val tags = imageTag.findAll(svg).map { it.value }.toList()
    val stripped = svg.replace(imageTag, "").replace("</image>", "", ignoreCase = true)
    val root = rootTag.find(stripped)?.value ?: return null

    val viewBox = attr(root, "viewBox")
        ?.trim()
        ?.split(separators)
        ?.mapNotNull { it.toFloatOrNull() }
        ?.takeIf { it.size == 4 }
    val width = length(attr(root, "width")) ?: viewBox?.get(2) ?: return null
    val height = length(attr(root, "height")) ?: viewBox?.get(3) ?: return null
    if (width <= 0f || height <= 0f) return null
    val box = viewBox ?: listOf(0f, 0f, width, height)
    val sx = width / box[2]
    val sy = height / box[3]

    val images = tags.mapNotNull { tag ->
        val href = (attr(tag, "href") ?: attr(tag, "xlink:href"))?.replace("&amp;", "&") ?: return@mapNotNull null
        EmbeddedImage(
            href = href,
            x = length(attr(tag, "x")) ?: 0f,
            y = length(attr(tag, "y")) ?: 0f,
            width = length(attr(tag, "width")) ?: return@mapNotNull null,
            height = length(attr(tag, "height")) ?: return@mapNotNull null,
            preserveAspectRatio = attr(tag, "preserveAspectRatio").orEmpty(),
        )
    }
    val loaded = coroutineScope {
        images.map { async { loadImage(it.href) } }.map { it.await() }
    }

    val scale = (TARGET_WIDTH / width).coerceIn(1f, MAX_SCALE)
    val bitmap = Bitmap().apply {
        allocPixels(ImageInfo.makeN32Premul((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1)))
    }
    val dom = SVGDOM(Data.makeFromBytes(stripped.encodeToByteArray()))
    val canvas = Canvas(bitmap)
    try {
        dom.setContainerSize(width, height)
        canvas.scale(scale, scale)
        dom.render(canvas)
        images.forEachIndexed { index, embedded ->
            val image = loaded[index] ?: return@forEachIndexed
            val dx = (embedded.x - box[0]) * sx
            val dy = (embedded.y - box[1]) * sy
            val dw = embedded.width * sx
            val dh = embedded.height * sy
            val dst = fit(image.width.toFloat(), image.height.toFloat(), dx, dy, dw, dh, embedded.preserveAspectRatio)
            canvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                dst,
                FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                null,
                true,
            )
        }
    } finally {
        canvas.close()
        dom.close()
        loaded.forEach { it?.close() }
    }
    return bitmap
}

private fun fit(iw: Float, ih: Float, x: Float, y: Float, w: Float, h: Float, preserveAspectRatio: String): Rect {
    val parts = preserveAspectRatio.trim().split(separators).filter { it.isNotEmpty() && it != "defer" }
    val align = parts.firstOrNull() ?: "xMidYMid"
    if (align == "none") return Rect.makeXYWH(x, y, w, h)
    val slice = parts.getOrNull(1) == "slice"
    val ratioW = w / iw
    val ratioH = h / ih
    val s = if (slice) maxOf(ratioW, ratioH) else minOf(ratioW, ratioH)
    val fw = iw * s
    val fh = ih * s
    val ox = when {
        align.startsWith("xMin") -> 0f
        align.startsWith("xMax") -> w - fw
        else -> (w - fw) / 2f
    }
    val oy = when {
        align.endsWith("YMin") -> 0f
        align.endsWith("YMax") -> h - fh
        else -> (h - fh) / 2f
    }
    return Rect.makeXYWH(x + ox, y + oy, fw, fh)
}

private fun attr(tag: String, name: String): String? {
    val match = Regex("(?<![\\w:-])${Regex.escape(name)}\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").find(tag) ?: return null
    return match.groupValues[1].ifEmpty { match.groupValues[2] }
}

private fun length(value: String?): Float? =
    value?.trim()?.removeSuffix("px")?.toFloatOrNull()

private fun decodeDataUri(uri: String): Image? {
    val comma = uri.indexOf(',')
    if (comma < 0) return null
    val payload = uri.substring(comma + 1)
    val bytes = if (uri.substring(0, comma).contains("base64", ignoreCase = true)) {
        Base64.getMimeDecoder().decode(payload)
    } else {
        URLDecoder.decode(payload, "UTF-8").encodeToByteArray()
    }
    return Image.makeFromEncoded(bytes)
}

class SvgEmbeddedImageDecoder(
    private val svg: String,
    private val options: Options,
    private val imageLoader: ImageLoader,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val bitmap = renderSvgWithImages(svg, ::load) ?: return null
        return DecodeResult(image = bitmap.asImage(), isSampled = false)
    }

    private suspend fun load(href: String): Image? = runCatching {
        if (href.startsWith("data:", ignoreCase = true)) return@runCatching decodeDataUri(href)
        val request = ImageRequest.Builder(options.context)
            .data(href)
            .size(Size.ORIGINAL)
            .build()
        val result = imageLoader.execute(request) as? SuccessResult ?: return@runCatching null
        Image.makeFromBitmap(result.image.toBitmap())
    }.getOrNull()

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val isSvg = result.mimeType?.contains("svg", ignoreCase = true) == true ||
                result.source.fileOrNull()?.name?.endsWith(".svg", ignoreCase = true) == true
            if (!isSvg) return null
            val svg = runCatching { result.source.source().peek().readUtf8() }.getOrNull() ?: return null
            if (!imageTag.containsMatchIn(svg)) return null
            return SvgEmbeddedImageDecoder(svg, options, imageLoader)
        }
    }
}
