package com.nuvio.app.features.home.components

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

private const val MAX_FRAME_DIMENSION = 512

// How many decoded gif bytes we're willing to keep on disk between app runs.
private const val MAX_DISK_CACHE_FILES = 200

private val desktopGifHttpClient by lazy {
    HttpClient(CIO) {
        followRedirects = true
        engine {
            requestTimeout = 15_000
        }
    }
}

private val downloadSemaphore = Semaphore(4)

// Disk-persisted cache of raw gif bytes, so a gif that was ever hovered doesn't need to hit
// the network again on the next app launch - only the very first hover ever pays network cost.
private val gifDiskCacheDir: Path by lazy {
    DesktopStorage.cacheDir.resolve("gif-cache").also {
        runCatching { Files.createDirectories(it) }
    }
}

private fun gifDiskCacheFile(url: String): Path {
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return gifDiskCacheDir.resolve("$hash.gif")
}

private fun readGifDiskCache(url: String): ByteArray? {
    val file = gifDiskCacheFile(url)
    return runCatching {
        if (Files.exists(file)) Files.readAllBytes(file) else null
    }.getOrNull()
}

private fun writeGifDiskCache(url: String, bytes: ByteArray) {
    runCatching {
        val target = gifDiskCacheFile(url)
        Files.createDirectories(target.parent)
        val pending = Files.createTempFile(target.parent, target.fileName.toString(), ".part")
        try {
            Files.write(pending, bytes)
            runCatching {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(pending)
        }
        trimGifDiskCache()
    }
}

// Best-effort LRU trim so the cache folder doesn't grow unbounded over many app sessions.
private fun trimGifDiskCache() {
    runCatching {
        val files = Files.list(gifDiskCacheDir).use { it.toList() }
        if (files.size <= MAX_DISK_CACHE_FILES) return
        files
            .sortedBy { runCatching { Files.getLastModifiedTime(it) }.getOrNull() }
            .take(files.size - MAX_DISK_CACHE_FILES)
            .forEach { runCatching { Files.deleteIfExists(it) } }
    }
}

private class GifCodecHolder(
    val codec: Codec,
    val frameDelaysMs: List<Long>,
    val width: Int,
    val height: Int,
    val targetWidth: Int,
    val targetHeight: Int,
    val needsScale: Boolean,
) {
    @Volatile
    var predecodedFrames: List<ImageBitmap>? = null
    var frameTimestampsMs: LongArray? = null
    var totalDurationMs: Long = 0L

    fun predecodeAllFrames() {
        if (predecodedFrames != null) return
        val count = codec.frameCount
        if (count <= 0) return
        val decoded = ArrayList<ImageBitmap>(count)
        val timestamps = LongArray(count)
        var cumulative = 0L

        val fullBitmap = if (needsScale) {
            try {
                Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(width, height)) }
            } catch (_: Exception) { null }
        } else null

        try {
            for (i in 0 until count) {
                timestamps[i] = cumulative
                val delay = frameDelaysMs.getOrElse(i) { 100L }.coerceAtLeast(16L)
                cumulative += delay

                val frameBitmap = Bitmap().apply {
                    allocPixels(ImageInfo.makeN32Premul(targetWidth, targetHeight))
                }
                if (needsScale && fullBitmap != null) {
                    codec.readPixels(fullBitmap, i)
                    val skiaImg = Image.makeFromBitmap(fullBitmap)
                    try {
                        Canvas(frameBitmap).drawImageRect(
                            skiaImg,
                            Rect.makeWH(width.toFloat(), height.toFloat()),
                            Rect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()),
                            SamplingMode.LINEAR,
                            null,
                            true,
                        )
                    } finally {
                        skiaImg.close()
                    }
                } else {
                    codec.readPixels(frameBitmap, i)
                }
                frameBitmap.setImmutable()
                decoded.add(frameBitmap.asComposeImageBitmap())
            }
            this.predecodedFrames = decoded
            this.frameTimestampsMs = timestamps
            this.totalDurationMs = cumulative.coerceAtLeast(1L)
        } catch (_: Exception) {
            // Keep predecodedFrames null on failure
        } finally {
            try { fullBitmap?.close() } catch (_: Exception) {}
        }
    }

    fun frameIndexAt(elapsedMs: Long): Int {
        val frames = predecodedFrames ?: return 0
        if (frames.size <= 1 || totalDurationMs <= 0L) return 0
        val loopTime = elapsedMs % totalDurationMs
        val timestamps = frameTimestampsMs ?: return 0
        for (i in timestamps.indices.reversed()) {
            if (loopTime >= timestamps[i]) return i
        }
        return 0
    }

    fun remainingDelayForFrame(elapsedMs: Long, currentIndex: Int): Long {
        val timestamps = frameTimestampsMs ?: return 100L
        val nextIndex = (currentIndex + 1) % timestamps.size
        val total = totalDurationMs
        val loopTime = elapsedMs % total
        val nextTime = if (nextIndex == 0) total else timestamps[nextIndex]
        return (nextTime - loopTime).coerceIn(16L, 500L)
    }
}

// LRU Cache holding raw GIF Codecs (max 15 items in memory, tiny compressed bytes ~10-20MB total)
private val gifCodecCache = object : LinkedHashMap<String, GifCodecHolder?>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GifCodecHolder?>?): Boolean {
        // A visible/hovered card can retain this holder after cache eviction.
        // Codec is a Skia Managed object: let its cleaner release native memory
        // after the last user drops it. Explicit close here invalidates a live
        // card's codec and makes its next readPixels call crash in native code.
        return size > 15
    }
}

// Long-lived scope for background prefetch/decode work that should survive a single card's
// composition (e.g. the card scrolls off-screen right after a prefetch was kicked off).
private val gifLoaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// Tracks in-flight loads so two composables hovering/prefetching the same url concurrently
// share one download+decode instead of racing two network requests.
private val inFlightGifLoads = mutableMapOf<String, Deferred<GifCodecHolder?>>()

private suspend fun loadDesktopGifCodec(url: String): GifCodecHolder? {
    synchronized(gifCodecCache) {
        if (gifCodecCache.containsKey(url)) {
            return gifCodecCache[url]
        }
    }

    val deferred = synchronized(inFlightGifLoads) {
        inFlightGifLoads.getOrPut(url) {
            gifLoaderScope.async { downloadSemaphore.withPermit { decodeGifCodec(url) } }
        }
    }

    val holder = try {
        deferred.await()
    } finally {
        synchronized(inFlightGifLoads) {
            if (inFlightGifLoads[url] === deferred) inFlightGifLoads.remove(url)
        }
    }

    synchronized(gifCodecCache) {
        gifCodecCache[url] = holder
    }
    return holder
}

private suspend fun decodeGifCodec(url: String): GifCodecHolder? {
    return try {
        // Disk cache first - avoids a network round-trip on every fresh app launch.
        val bytes = readGifDiskCache(url) ?: run {
            val downloaded = desktopGifHttpClient.get(url) {
                header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                )
            }.body<ByteArray>()
            writeGifDiskCache(url, downloaded)
            downloaded
        }

        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        val count = codec.frameCount
        if (count <= 1) return null
        val w = codec.width
        val h = codec.height
        if (w <= 0 || h <= 0) return null

        val scale = if (w > MAX_FRAME_DIMENSION || h > MAX_FRAME_DIMENSION) {
            minOf(MAX_FRAME_DIMENSION.toFloat() / w, MAX_FRAME_DIMENSION.toFloat() / h)
        } else 1f

        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        val needsScale = tw != w || th != h

        val delays = List(count) { i ->
            val duration = codec.getFrameInfo(i).duration
            if (duration > 0) duration.toLong() else 100L
        }
        val holder = GifCodecHolder(codec, delays, w, h, tw, th, needsScale)
        holder.predecodeAllFrames()
        holder
    } catch (_: Exception) {
        null
    }
}

private fun calculateCropSrcRect(
    srcWidth: Int,
    srcHeight: Int,
    dstWidth: Float,
    dstHeight: Float,
): Pair<IntOffset, IntSize> {
    if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0f || dstHeight <= 0f) {
        return IntOffset.Zero to IntSize(srcWidth.coerceAtLeast(1), srcHeight.coerceAtLeast(1))
    }
    val srcRatio = srcWidth.toFloat() / srcHeight
    val dstRatio = dstWidth / dstHeight
    return if (srcRatio > dstRatio) {
        val visibleWidth = (srcHeight * dstRatio).roundToInt().coerceIn(1, srcWidth)
        val xOffset = (srcWidth - visibleWidth) / 2
        IntOffset(xOffset, 0) to IntSize(visibleWidth, srcHeight)
    } else {
        val visibleHeight = (srcWidth / dstRatio).roundToInt().coerceIn(1, srcHeight)
        val yOffset = (srcHeight - visibleHeight) / 2
        IntOffset(0, yOffset) to IntSize(srcWidth, visibleHeight)
    }
}

@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    staticImageUrl: String?,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
    animateWithoutFocus: Boolean,
) {
    val hoverInteractionSource = remember { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()

    val shouldAnimate = animateIfPossible &&
        (animateWithoutFocus || isHovered || staticImageUrl.isNullOrBlank())

    // Prefetch as soon as the card becomes visible (not on hover) so the codec is already
    // downloaded/decoded and cached by the time the user actually hovers - hover then reads
    // straight from gifCodecCache with zero network/decode delay.
    if (animateIfPossible) {
        LaunchedEffect(imageUrl) {
            if (synchronized(gifCodecCache) { !gifCodecCache.containsKey(imageUrl) }) {
                loadDesktopGifCodec(imageUrl)
            }
        }
    }

    var codecHolder by remember(imageUrl) {
        mutableStateOf(synchronized(gifCodecCache) { gifCodecCache[imageUrl] })
    }

    LaunchedEffect(imageUrl, shouldAnimate) {
        if (shouldAnimate && codecHolder == null) {
            codecHolder = loadDesktopGifCodec(imageUrl)
        }
    }

    val currentHolder = if (shouldAnimate) codecHolder else null
    val frameIndexState = remember(currentHolder) { mutableIntStateOf(0) }

    LaunchedEffect(currentHolder, shouldAnimate) {
        if (!shouldAnimate || currentHolder == null) return@LaunchedEffect

        if (currentHolder.predecodedFrames == null) {
            withContext(Dispatchers.Default) {
                currentHolder.predecodeAllFrames()
            }
        }

        val frames = currentHolder.predecodedFrames ?: return@LaunchedEffect
        if (frames.size <= 1) return@LaunchedEffect

        val startTime = System.currentTimeMillis()
        while (isActive) {
            val elapsed = System.currentTimeMillis() - startTime
            val currentIndex = currentHolder.frameIndexAt(elapsed)
            frameIndexState.intValue = currentIndex

            val waitMs = currentHolder.remainingDelayForFrame(elapsed, currentIndex)
            delay(waitMs)
        }
    }

    val context = LocalPlatformContext.current
    val displayImageUrl = if (animateIfPossible) {
        staticImageUrl?.takeIf { it.isNotBlank() } ?: imageUrl
    } else {
        imageUrl
    }
    val request = remember(context, displayImageUrl) {
        ImageRequest.Builder(context)
            .data(displayImageUrl)
            .memoryCacheKey("home-collection:$displayImageUrl")
            .diskCacheKey(displayImageUrl)
            .build()
    }

    Box(
        modifier = modifier
            .hoverable(hoverInteractionSource)
            .drawWithContent {
                drawContent()
                val holder = currentHolder
                val frames = holder?.predecodedFrames
                if (frames != null && frames.isNotEmpty()) {
                    val idx = frameIndexState.intValue.coerceIn(0, frames.lastIndex)
                    val frame = frames[idx]
                    val (srcOffset, srcSize) = calculateCropSrcRect(frame.width, frame.height, size.width, size.height)
                    drawImage(
                        image = frame,
                        srcOffset = srcOffset,
                        srcSize = srcSize,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = FilterQuality.Low,
                    )
                }
            },
    ) {
        // Stacked Base Layer: uses NuvioAsyncImage with desktop high-quality anti-aliased scaling
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize(),
            contentScale = contentScale,
            filterQuality = FilterQuality.High,
        )
    }
}
