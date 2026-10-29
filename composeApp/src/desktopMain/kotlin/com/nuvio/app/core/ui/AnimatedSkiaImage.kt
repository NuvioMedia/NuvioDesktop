package com.nuvio.app.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil3.Extras
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * Per-request switch for animated playback of GIF / animated WebP images.
 *
 * `true` decodes animated images into an [AnimatedSkiaImage]; unset or `false` keeps a still first frame.
 */
internal val NuvioAnimatedImageKey = Extras.Key<Boolean?>(default = null)

/**
 * Animations are limited by pixel count rather than by edge length, so a 1280x720 background and a
 * 400x140 badge both qualify while a huge animation falls back to a still frame instead of eating RAM/CPU.
 */
internal const val MaxAnimatedImagePixels = 1_300_000

/** Animations decoded at the same time. Extra animations stay on their current frame until one stops. */
private const val MaxConcurrentPlayers = 12

/** Threads used for frame decoding. Compose keeps the UI thread free of decode work. */
private const val PlaybackThreads = 3

/** A player that has not been drawn for this long is considered off screen and stops. */
private const val StaleAfterMs = 400L
private const val MaxSleepMs = 100L
private const val MinSleepMs = 4L
private const val FrameRingSize = 3

/** Frames are only downscaled ahead of time when the target is clearly smaller than the source. */
private const val MinDownscaleRatio = 1.5f
private const val MipmapRatio = 2.5f

/**
 * Coil image holding an encoded animation. Used as a plain Coil image it draws its first frame; use
 * [painter] for animated playback.
 */
internal class AnimatedSkiaImage(
    val data: Data,
    override val width: Int,
    override val height: Int,
    val frameDelaysMs: IntArray,
    /** For each frame, the earlier frame that must already be on the canvas (-1 when independent). */
    val requiredFrames: IntArray,
    /** Extra plays after the first one, or -1 to loop forever. */
    val repetitionCount: Int,
    val firstFrame: SkiaImage,
    /** Hash of the encoded bytes. Identical animations share one player however many times they were loaded. */
    val contentKey: String,
) : coil3.Image {

    override val size: Long = data.size.toLong() + width.toLong() * height.toLong() * 4L

    override val shareable: Boolean = true

    override fun draw(canvas: Canvas) {
        canvas.drawImage(firstFrame, 0f, 0f)
    }

    /**
     * One player per distinct animation. Coil does not merge concurrent loads of one URL, so the same badge
     * shown in forty chips is decoded forty times; sharing the player keeps it to a single frame decoder.
     */
    internal val player: FramePlayer = FramePlayerRegistry.obtain(this)

    /**
     * Painter that plays the animation while [playing] returns true and shows the first frame otherwise.
     * [playing] is read while drawing, so a change redraws without recomposing.
     */
    fun painter(playing: () -> Boolean): Painter = AnimatedSkiaPainter(this, playing)
}

private class AnimatedSkiaPainter(
    private val source: AnimatedSkiaImage,
    private val playing: () -> Boolean,
) : Painter() {
    // One native paint per painter, reused for every draw.
    private val paint = SkiaPaint()
    private var wasPlaying = false

    override val intrinsicSize: Size = Size(source.width.toFloat(), source.height.toFloat())

    override fun DrawScope.onDraw() {
        val drawSize = IntSize(
            width = size.width.roundToInt().coerceAtLeast(1),
            height = size.height.roundToInt().coerceAtLeast(1),
        )
        val isPlaying = playing()
        // A one-shot animation that already ended only plays again when playback starts anew (a fresh hover,
        // or the image coming back into composition), never because something merely redrew it.
        if (isPlaying && !wasPlaying) source.player.restartIfFinished()
        wasPlaying = isPlaying
        val image = if (isPlaying) {
            // Drawing is the only signal that someone still sees this image: the player stops by itself
            // once draws stop, so playback never outlives the composable showing it.
            source.player.onDrawn(drawSize)
            source.player.frame
        } else {
            source.firstFrame
        }
        // The Skia image is drawn directly. Compose's ImageBitmap wrapper would allocate a native copy of every
        // frame that is only freed when the JVM garbage-collects, which grows native memory into gigabytes.
        drawIntoCanvas { canvas ->
            canvas.skiaCanvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeWH(drawSize.width.toFloat(), drawSize.height.toFloat()),
                SamplingMode.LINEAR,
                paint,
                true,
            )
        }
    }

    override fun applyAlpha(alpha: Float): Boolean {
        paint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
        return true
    }

    // Not handled here: returning false makes Compose draw this painter through a layer that applies the filter.
    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean = colorFilter == null
}

private fun nowMs(): Long = System.nanoTime() / 1_000_000L

private object FramePlayerRegistry {
    private val players = HashMap<String, WeakReference<FramePlayer>>()

    fun obtain(image: AnimatedSkiaImage): FramePlayer = synchronized(this) {
        players[image.contentKey]?.get()?.let { return it }
        if (players.size >= PruneThreshold) players.values.removeAll { it.get() == null }
        FramePlayer(image).also { players[image.contentKey] = WeakReference(it) }
    }

    private const val PruneThreshold = 128
}

private val activePlayers = AtomicInteger(0)

/** Animations that wanted to start while every slot was taken. The oldest waiter gets the next free slot. */
private val waitingPlayers = ArrayDeque<WeakReference<FramePlayer>>()

@OptIn(ExperimentalCoroutinesApi::class)
private val playbackScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default.limitedParallelism(PlaybackThreads),
)

/**
 * Decodes the frames of one animation in order into one reused working bitmap and publishes each frame,
 * downscaled to the size it is drawn at, through [frame].
 */
internal class FramePlayer(private val source: AnimatedSkiaImage) {
    /** The frame to draw while playing. Reading it in a draw scope redraws on every new frame. */
    var frame: SkiaImage by mutableStateOf(source.firstFrame)
        private set

    @Volatile private var lastDrawnAtMs = 0L
    @Volatile private var targetWidth = 0
    @Volatile private var targetHeight = 0
    @Volatile private var failed = false
    @Volatile private var queued = false

    private val lock = Any()
    private var job: Job? = null
    private var finished = false

    // Playback state, only touched by the single coroutine that is running this player.
    private var codec: Codec? = null
    private var work: Bitmap? = null
    private var frameIndex = 0
    private var lastDecoded = -1
    private var playsCompleted = 0
    private var nextDueAtMs = 0L

    // Published frames are closed a few frames later, once the UI thread can no longer be drawing them.
    private val ring = arrayOfNulls<SkiaImage>(FrameRingSize)
    private var ringPos = 0
    private var lastPublished: SkiaImage? = null

    fun onDrawn(drawSize: IntSize) {
        lastDrawnAtMs = nowMs()
        // The largest size it is drawn at wins, so a second, bigger use of the same image stays sharp.
        if (drawSize.width > targetWidth || drawSize.height > targetHeight) {
            targetWidth = max(targetWidth, drawSize.width)
            targetHeight = max(targetHeight, drawSize.height)
        }
        if (failed) return
        synchronized(lock) {
            if (job?.isActive == true || finished) return
            if (!tryStartLocked()) enqueue()
        }
    }

    /** Starts playback if a slot is free. Called with [lock] held. */
    private fun tryStartLocked(): Boolean {
        if (activePlayers.incrementAndGet() > MaxConcurrentPlayers) {
            activePlayers.decrementAndGet()
            return false
        }
        nextDueAtMs = 0L
        job = playbackScope.launch { run() }
        return true
    }

    private fun enqueue() {
        if (queued) return
        queued = true
        synchronized(waitingPlayers) { waitingPlayers.addLast(WeakReference(this)) }
    }

    /** Hands a freed slot to the longest-waiting animation. */
    private fun startNextWaiting() {
        while (true) {
            val next = synchronized(waitingPlayers) { waitingPlayers.removeFirstOrNull() }?.get() ?: return
            next.queued = false
            val started = synchronized(next.lock) {
                if (next.failed || next.finished || next.job?.isActive == true) false
                else {
                    // Grace period: it is only redrawn once it plays, and a draw refreshes this again. If it
                    // is not on screen any more nothing draws it and it stops after the usual delay.
                    next.lastDrawnAtMs = nowMs()
                    next.tryStartLocked()
                }
            }
            if (started) return
        }
    }

    /** Rewinds a one-shot animation that already ended so the next draw plays it again. */
    fun restartIfFinished() {
        synchronized(lock) {
            if (!finished) return
            finished = false
            frameIndex = 0
            playsCompleted = 0
        }
    }

    private suspend fun run() {
        try {
            while (true) {
                val now = nowMs()
                if (failed || finished || now - lastDrawnAtMs > StaleAfterMs) {
                    // Free native buffers before giving up the job slot, so a draw that arrives right now
                    // cannot start a second run while this one is still tearing down.
                    release()
                    val keepGoing = synchronized(lock) {
                        val stillVisible = !failed && !finished && nowMs() - lastDrawnAtMs <= StaleAfterMs
                        if (!stillVisible) job = null
                        stillVisible
                    }
                    if (!keepGoing) return
                    continue
                }
                if (now >= nextDueAtMs) advance(now)
                delay((nextDueAtMs - nowMs()).coerceIn(MinSleepMs, MaxSleepMs))
            }
        } finally {
            activePlayers.decrementAndGet()
            startNextWaiting()
        }
    }

    private fun advance(now: Long) {
        try {
            val frames = source.frameDelaysMs.size
            val activeCodec = codec ?: Codec.makeFromData(source.data).also { codec = it }
            val bitmap = work ?: Bitmap().also {
                it.allocPixels(ImageInfo.makeN32Premul(source.width, source.height))
                work = it
            }

            val index = frameIndex
            // Playing in order lets Skia build each frame on top of the previous one instead of
            // re-decoding every frame it depends on.
            val required = source.requiredFrames[index]
            val prior = if (required >= 0 && required == lastDecoded) required else -1
            activeCodec.readPixels(bitmap, index, prior)
            lastDecoded = index

            val image = scaledToTarget(SkiaImage.makeFromBitmap(bitmap))
            frame = image
            lastPublished = image
            ring[ringPos]?.takeIf { it !== image }?.close()
            ring[ringPos] = image
            ringPos = (ringPos + 1) % FrameRingSize

            val delayMs = source.frameDelaysMs[index]
            // Browsers treat very short GIF delays as 100 ms; matching that keeps timing familiar.
            nextDueAtMs = now + if (delayMs <= 10) 100L else delayMs.toLong()

            val next = index + 1
            if (next < frames) {
                frameIndex = next
            } else {
                playsCompleted++
                frameIndex = 0
                val repeats = source.repetitionCount
                if (repeats >= 0 && playsCompleted > repeats) finished = true
            }
        } catch (_: Throwable) {
            failed = true
        }
    }

    /** Resamples the frame to the size it is drawn at, here instead of on the UI thread every draw. */
    private fun scaledToTarget(image: SkiaImage): SkiaImage {
        val tw = targetWidth
        val th = targetHeight
        if (tw <= 0 || th <= 0) return image
        if (image.width < tw * MinDownscaleRatio || image.height < th * MinDownscaleRatio) return image

        // Quantized so a card that grows a few pixels does not rebuild the size every frame.
        val width = ((tw + 7) / 8) * 8
        val height = ((th + 7) / 8) * 8
        val surface = Surface.makeRasterN32Premul(width, height)
        try {
            // Mipmaps are rebuilt for every frame, so they are only worth it for a heavy downscale; a modest
            // one looks fine with plain bilinear sampling at a fraction of the cost.
            val heavyDownscale = image.width >= width * MipmapRatio || image.height >= height * MipmapRatio
            surface.canvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeWH(width.toFloat(), height.toFloat()),
                if (heavyDownscale) FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST) else SamplingMode.LINEAR,
                null,
                true,
            )
            val scaled = surface.makeImageSnapshot()
            image.close()
            return scaled
        } finally {
            surface.close()
        }
    }

    /** Frees native memory while keeping the last frame on screen. */
    private fun release() {
        runCatching { codec?.close() }
        runCatching { work?.close() }
        codec = null
        work = null
        lastDecoded = -1
        for (i in ring.indices) {
            val image = ring[i]
            if (image != null && image !== lastPublished) runCatching { image.close() }
            ring[i] = null
        }
        lastPublished?.let { ring[0] = it }
        ringPos = if (lastPublished != null) 1 else 0
    }
}
