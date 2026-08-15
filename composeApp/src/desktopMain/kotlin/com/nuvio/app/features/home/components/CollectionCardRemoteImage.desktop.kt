package com.nuvio.app.features.home.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import java.io.File
import java.security.MessageDigest

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Max pixel dimension (width or height) for decoded GIF frames. */
private const val MAX_FRAME_DIMENSION = 512

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

private class DesktopGifAnimation(
    val composeBitmaps: List<ImageBitmap>,
    val delaysMs: List<Long>,
    val estimatedBytes: Long,
)

// ---------------------------------------------------------------------------
// In-memory LRU cache (decoded frames)
// ---------------------------------------------------------------------------

private object DesktopGifCache {
    private const val MAX_ENTRIES = 24
    private const val MAX_BYTES = 60L * 1024L * 1024L // 60 MB

    private val cache = LinkedHashMap<String, DesktopGifAnimation>(16, 0.75f, true)
    private var totalBytes = 0L

    @Synchronized
    fun get(url: String): DesktopGifAnimation? = cache[url]

    @Synchronized
    fun put(url: String, animation: DesktopGifAnimation) {
        cache.remove(url)?.let { totalBytes -= it.estimatedBytes }
        cache[url] = animation
        totalBytes += animation.estimatedBytes
        trimToSize()
    }

    @Synchronized
    private fun trimToSize() {
        val iter = cache.entries.iterator()
        while (iter.hasNext() && (cache.size > MAX_ENTRIES || totalBytes > MAX_BYTES)) {
            totalBytes -= iter.next().value.estimatedBytes
            iter.remove()
        }
    }
}

// ---------------------------------------------------------------------------
// Persistent disk cache (raw GIF bytes)
// ---------------------------------------------------------------------------

private val diskCacheDir: File by lazy {
    val base = System.getProperty("user.home")
        ?.let { File(it, ".nuvio/gif-cache") }
        ?: File(System.getProperty("java.io.tmpdir"), "nuvio_desktop_gif_cache")
    base.apply { mkdirs() }
}

/** SHA-256 hash of the URL, used as a collision-free filename. */
private fun urlToFilename(url: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
    return digest.joinToString("") { "%02x".format(it) } + ".gif"
}

private fun readDiskCache(url: String): ByteArray? = runCatching {
    val file = File(diskCacheDir, urlToFilename(url))
    if (file.exists() && file.length() > 0) file.readBytes() else null
}.getOrNull()

private fun writeDiskCache(url: String, bytes: ByteArray) = runCatching {
    File(diskCacheDir, urlToFilename(url)).writeBytes(bytes)
}

// ---------------------------------------------------------------------------
// Networking
// ---------------------------------------------------------------------------

private val gifHttpClient by lazy {
    HttpClient(CIO) {
        followRedirects = true
        engine { requestTimeout = 15_000 }
    }
}

private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val inFlight = mutableMapOf<String, Deferred<DesktopGifAnimation?>>()

// ---------------------------------------------------------------------------
// Skia GIF decoder
// ---------------------------------------------------------------------------

private fun decodeSkiaGif(bytes: ByteArray): DesktopGifAnimation? {
    if (bytes.isEmpty()) return null

    return Data.makeFromBytes(bytes).use { data ->
        Codec.makeFromData(data).use { codec ->
            val count = codec.frameCount
            val w = codec.width
            val h = codec.height
            if (count <= 1 || w <= 0 || h <= 0) return null

            val scale = if (w > MAX_FRAME_DIMENSION || h > MAX_FRAME_DIMENSION) {
                minOf(MAX_FRAME_DIMENSION.toFloat() / w, MAX_FRAME_DIMENSION.toFloat() / h)
            } else 1f

            val tw = (w * scale).toInt().coerceAtLeast(1)
            val th = (h * scale).toInt().coerceAtLeast(1)
            val needsScale = tw != w || th != h

            val frames = ArrayList<ImageBitmap>(count)
            val delays = ArrayList<Long>(count)
            var totalBytes = 0L

            // Reusable full-res buffer when downscaling
            val fullBitmap = if (needsScale) {
                Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(w, h)) }
            } else null

            try {
                for (i in 0 until count) {
                    val dur = codec.getFrameInfo(i).duration
                    delays += if (dur > 10) dur.toLong() else 100L

                    val frame: Bitmap
                    if (needsScale && fullBitmap != null) {
                        codec.readPixels(fullBitmap, i)
                        frame = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(tw, th)) }
                        val skiaImg = Image.makeFromBitmap(fullBitmap)
                        try {
                            Canvas(frame).drawImageRect(
                                skiaImg,
                                Rect.makeWH(w.toFloat(), h.toFloat()),
                                Rect.makeWH(tw.toFloat(), th.toFloat()),
                            )
                        } finally {
                            skiaImg.close()
                        }
                    } else {
                        frame = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(tw, th)) }
                        codec.readPixels(frame, i)
                    }
                    frame.setImmutable()
                    frames += frame.asComposeImageBitmap()
                    totalBytes += tw.toLong() * th * 4L
                }
            } finally {
                fullBitmap?.close()
            }

            if (frames.isEmpty()) return null
            DesktopGifAnimation(frames, delays, totalBytes)
        }
    }
}

// ---------------------------------------------------------------------------
// Loading pipeline: memory cache → disk cache → network → decode
// ---------------------------------------------------------------------------

private val downloadSemaphore = kotlinx.coroutines.sync.Semaphore(4)

private suspend fun loadGifAnimation(url: String): DesktopGifAnimation? {
    DesktopGifCache.get(url)?.let { return it }

    val deferred = synchronized(inFlight) {
        inFlight.getOrPut(url) {
            decodeScope.async {
                downloadSemaphore.acquire()
                try {
                    runCatching {
                        val bytes = readDiskCache(url) ?: run {
                            val fetched = gifHttpClient.get(url) {
                                header(
                                    "User-Agent",
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                )
                            }.body<ByteArray>()
                            if (fetched.isNotEmpty()) writeDiskCache(url, fetched)
                            fetched
                        }
                        decodeSkiaGif(bytes)
                    }.getOrNull()
                } finally {
                    downloadSemaphore.release()
                }
            }
        }
    }

    val anim = try {
        deferred.await()
    } finally {
        synchronized(inFlight) {
            if (inFlight[url] === deferred) inFlight.remove(url)
        }
    }

    if (anim != null) DesktopGifCache.put(url, anim)
    return anim
}

// ---------------------------------------------------------------------------
// Composable
// ---------------------------------------------------------------------------

@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    staticImageUrl: String?,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
) {
    if (animateIfPossible && imageUrl.isNotBlank()) {
        var animation by remember(imageUrl) { mutableStateOf(DesktopGifCache.get(imageUrl)) }

        // Pre-load GIF in background as soon as card enters composition
        LaunchedEffect(imageUrl) {
            if (animation == null) {
                animation = loadGifAnimation(imageUrl)
            }
        }

        val anim = animation

        val context = LocalPlatformContext.current
        val coverUrl = staticImageUrl?.takeIf { it.isNotBlank() } ?: imageUrl
        val coverRequest = remember(context, coverUrl) {
            ImageRequest.Builder(context)
                .data(coverUrl)
                .memoryCacheKey("home-collection:$coverUrl")
                .diskCacheKey(coverUrl)
                .build()
        }

        Box(modifier = modifier) {
            // Static cover image (base layer, visible while GIF loads)
            AsyncImage(
                model = coverRequest,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
            )

            // Animated GIF overlay (auto-plays when decoded)
            val frames = anim?.composeBitmaps.orEmpty()
            val delays = anim?.delaysMs.orEmpty()

            if (frames.isNotEmpty()) {
                var frameIndex by remember(imageUrl) { mutableStateOf(0) }

                LaunchedEffect(imageUrl, anim) {
                    frameIndex = 0
                    while (true) {
                        val ms = delays.getOrElse(frameIndex) { 100L }
                        delay(ms)
                        frameIndex = (frameIndex + 1) % frames.size
                    }
                }

                Image(
                    bitmap = frames[frameIndex],
                    contentDescription = contentDescription,
                    modifier = Modifier.matchParentSize(),
                    contentScale = contentScale,
                )
            }
        }
        return
    }

    // Non-animated fallback
    val context = LocalPlatformContext.current
    val request = remember(context, imageUrl) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .memoryCacheKey("home-collection:$imageUrl")
            .diskCacheKey(imageUrl)
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}
