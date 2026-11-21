package com.nuvio.app.features.trailer.desktop

import androidx.compose.runtime.IntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.nuvio.app.features.trailer.TrailerExtractionPlatform
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.roundToInt

private const val MaxFrameWidth = 1920
private const val MaxFrameHeight = 1080
private const val MinFrameSide = 16
private const val FirstFrameTimeoutNanos = 15_000_000_000L
private const val EventWaitSeconds = 0.008
private const val EndOfFilePollIntervalNanos = 250_000_000L
private const val OpaqueAlphaMask = 0xFF000000.toInt()

/**
 * Plays one trailer with libmpv's software render API and exposes the frames as Compose images.
 *
 * Every libmpv call happens on the player's own thread, so creating and tearing down a player never
 * blocks the UI thread and a late render can never touch a destroyed handle.
 */
internal class NativeMpvSurfacePlayer(
    private val videoUrl: String,
    private val audioUrl: String?,
    private val startPositionMillis: Long,
    playWhenReady: Boolean,
    initialMuted: Boolean,
    private val fillFrame: Boolean,
    private val scope: CoroutineScope,
    private val onReady: () -> Unit,
    private val onEnded: () -> Unit,
    private val onError: () -> Unit,
) {
    private class FrameBuffer(val bitmap: Bitmap, val pixels: Pointer, val width: Int, val height: Int) {
        val image: ImageBitmap = bitmap.asComposeImageBitmap()
    }

    @Volatile private var disposed = false
    @Volatile private var paused = !playWhenReady
    @Volatile private var muted = initialMuted
    @Volatile private var requestedSize = 0L
    @Volatile private var frontBuffer: FrameBuffer? = null

    private val frameCounter = mutableIntStateOf(0)

    /** Changes whenever [currentFrame] holds new pixels; read it while drawing to redraw on each frame. */
    val frameTick: IntState get() = frameCounter

    val currentFrame: ImageBitmap? get() = frontBuffer?.image

    init {
        thread(name = "nuvio-trailer-mpv", isDaemon = true) { run() }
    }

    fun setSize(width: Int, height: Int) {
        if (width < MinFrameSide || height < MinFrameSide) return
        val scale = minOf(1f, MaxFrameWidth.toFloat() / width, MaxFrameHeight.toFloat() / height)
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(MinFrameSide)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(MinFrameSide)
        requestedSize = (targetWidth.toLong() shl 32) or targetHeight.toLong()
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    fun dispose() {
        disposed = true
    }

    private fun run() {
        val api = LibMpv.instance
        if (api == null) {
            report("libmpv unavailable", onError)
            return
        }
        var mpv: Pointer? = null
        var renderContext: Pointer? = null
        try {
            mpv = api.mpv_create() ?: error("mpv_create failed")
            configure(api, mpv)
            check(api.mpv_initialize(mpv), api, "mpv_initialize")
            renderContext = createRenderContext(api, mpv)
            check(api.mpv_command(mpv, loadCommand()), api, "loadfile")
            TrailerExtractionPlatform.diagnostic(
                "mpv surface open ${TrailerExtractionPlatform.describeUrl(videoUrl)} " +
                    "separateAudio=${!audioUrl.isNullOrBlank()} startMs=$startPositionMillis",
            )
            playbackLoop(api, mpv, renderContext)
        } catch (error: Throwable) {
            if (!disposed) {
                report("mpv surface failed detail=${error.message}", onError)
            }
        } finally {
            frontBuffer = null
            renderContext?.let { runCatching { api.mpv_render_context_free(it) } }
            mpv?.let { runCatching { api.mpv_terminate_destroy(it) } }
        }
    }

    private fun configure(api: LibMpv, mpv: Pointer) {
        fun option(name: String, value: String) = api.mpv_set_option_string(mpv, name, value)
        option("vo", "libmpv")
        option("hwdec", "auto-copy")
        option("ytdl", "no")
        option("osc", "no")
        option("input-default-bindings", "no")
        option("input-vo-keyboard", "no")
        option("sid", "no")
        option("audio-display", "no")
        option("keep-open", "yes")
        option("loop-file", "no")
        option("volume", "100")
        option("mute", if (muted) "yes" else "no")
        option("pause", if (paused) "yes" else "no")
        option("panscan", if (fillFrame) "1.0" else "0.0")
        option("cache", "yes")
        option("demuxer-max-bytes", "64MiB")
        option("demuxer-readahead-secs", "2")
        TrailerExtractionPlatform.defaultHeaders["user-agent"]?.let { option("user-agent", it) }
        if (startPositionMillis > 0L) {
            option("start", "%d.%03d".format(startPositionMillis / 1_000L, startPositionMillis % 1_000L))
        }
    }

    private fun loadCommand(): Array<String> {
        if (audioUrl.isNullOrBlank()) return arrayOf("loadfile", videoUrl)
        // %<byte length>% quoting passes the URL verbatim, so its commas and semicolons are never split.
        val audioLength = audioUrl.toByteArray(Charsets.UTF_8).size
        return arrayOf("loadfile", videoUrl, "replace", "-1", "audio-files-append=%$audioLength%$audioUrl")
    }

    private fun createRenderContext(api: LibMpv, mpv: Pointer): Pointer {
        val apiType = nativeString("sw")
        val params = Memory(RenderParamSize * 2L)
        params.clear()
        params.setInt(0L, LibMpv.RENDER_PARAM_API_TYPE)
        params.setPointer(8L, apiType)
        val result = PointerByReference()
        check(api.mpv_render_context_create(result, mpv, params), api, "mpv_render_context_create")
        return result.value ?: error("mpv_render_context_create returned no context")
    }

    private fun playbackLoop(api: LibMpv, mpv: Pointer, renderContext: Pointer) {
        val renderer = SoftwareRenderer(api, renderContext)
        var buffers: Array<FrameBuffer>? = null
        var backIndex = 0
        var appliedPaused = paused
        var appliedMuted = muted
        var ready = false
        var ended = false
        val startedAt = System.nanoTime()
        var lastEndCheckAt = startedAt

        while (!disposed) {
            val event = api.mpv_wait_event(mpv, EventWaitSeconds)
            when (event?.getInt(0L) ?: LibMpv.EVENT_NONE) {
                LibMpv.EVENT_SHUTDOWN -> return
                LibMpv.EVENT_END_FILE -> {
                    val data = event?.getPointer(16L)
                    if (data != null && data.getInt(0L) == LibMpv.END_FILE_REASON_ERROR) {
                        error("playback error ${api.mpv_error_string(data.getInt(4L))}")
                    }
                }
            }
            if (disposed) return

            if (paused != appliedPaused) {
                appliedPaused = paused
                api.mpv_set_property_string(mpv, "pause", if (appliedPaused) "yes" else "no")
            }
            if (muted != appliedMuted) {
                appliedMuted = muted
                api.mpv_set_property_string(mpv, "mute", if (appliedMuted) "yes" else "no")
            }

            val size = requestedSize
            val width = (size ushr 32).toInt()
            val height = size.toInt()
            var forceRender = false
            if (width > 0 && height > 0 && (buffers == null || buffers[0].width != width || buffers[0].height != height)) {
                buffers = arrayOf(allocateBuffer(width, height), allocateBuffer(width, height))
                backIndex = 0
                forceRender = ready
            }

            val frameAvailable = (api.mpv_render_context_update(renderContext) and LibMpv.RENDER_UPDATE_FRAME) != 0L
            if (buffers != null && (frameAvailable || forceRender)) {
                val target = buffers[backIndex]
                if (renderer.render(target)) {
                    backIndex = 1 - backIndex
                    frontBuffer = target
                    frameCounter.intValue++
                    if (!ready) {
                        ready = true
                        report("mpv surface ready with first frame ${target.width}x${target.height}", onReady)
                    }
                }
            }

            val now = System.nanoTime()
            if (!ready && now - startedAt > FirstFrameTimeoutNanos) {
                error("no frame within ${FirstFrameTimeoutNanos / 1_000_000_000L}s")
            }
            if (ready && !ended && now - lastEndCheckAt > EndOfFilePollIntervalNanos) {
                lastEndCheckAt = now
                if (readFlag(api, mpv, "eof-reached")) {
                    ended = true
                    report("mpv surface ended", onEnded)
                }
            }
        }
    }

    private fun allocateBuffer(width: Int, height: Int): FrameBuffer {
        val bitmap = Bitmap()
        val info = ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.OPAQUE, ColorSpace.sRGB), width, height)
        check(bitmap.allocPixels(info)) { "unable to allocate ${width}x$height trailer frame" }
        bitmap.erase(0xFF000000.toInt())
        val address = bitmap.peekPixels()?.addr ?: 0L
        check(address != 0L) { "trailer frame has no pixel storage" }
        return FrameBuffer(bitmap, Pointer(address), width, height)
    }

    private fun readFlag(api: LibMpv, mpv: Pointer, name: String): Boolean {
        val value = api.mpv_get_property_string(mpv, name) ?: return false
        return try {
            value.getString(0L) == "yes"
        } finally {
            api.mpv_free(value)
        }
    }

    private fun check(result: Int, api: LibMpv, stage: String) {
        if (result < 0) error("$stage failed: ${api.mpv_error_string(result)}")
    }

    private fun report(message: String, callback: () -> Unit) {
        TrailerExtractionPlatform.diagnostic(message)
        if (disposed) return
        // The scope belongs to the composition: callbacks run on the UI thread and are dropped once it leaves.
        scope.launch {
            if (!disposed) callback()
        }
    }

    private class SoftwareRenderer(private val api: LibMpv, private val renderContext: Pointer) {
        private val size = Memory(8L)
        private val stride = Memory(Native.SIZE_T_SIZE.toLong())
        private val format = nativeString("bgr0")
        private val params = Memory(RenderParamSize * 5L).apply {
            clear()
            setInt(0L, LibMpv.RENDER_PARAM_SW_SIZE)
            setPointer(8L, size)
            setInt(RenderParamSize, LibMpv.RENDER_PARAM_SW_FORMAT)
            setPointer(RenderParamSize + 8L, format)
            setInt(RenderParamSize * 2L, LibMpv.RENDER_PARAM_SW_STRIDE)
            setPointer(RenderParamSize * 2L + 8L, stride)
            setInt(RenderParamSize * 3L, LibMpv.RENDER_PARAM_SW_POINTER)
        }

        fun render(target: FrameBuffer): Boolean {
            size.setInt(0L, target.width)
            size.setInt(4L, target.height)
            val rowBytes = target.width.toLong() * 4L
            if (Native.SIZE_T_SIZE == 8) stride.setLong(0L, rowBytes) else stride.setInt(0L, rowBytes.toInt())
            params.setPointer(RenderParamSize * 3L + 8L, target.pixels)
            if (api.mpv_render_context_render(renderContext, params) < 0) return false
            forceOpaque(target)
            target.bitmap.notifyPixelsChanged()
            return true
        }

        // "bgr0" leaves the fourth byte undefined; only rewrite the frame when it is not already opaque.
        private fun forceOpaque(target: FrameBuffer) {
            val pixelCount = target.width * target.height
            val pixels = target.pixels
            val alreadyOpaque = intArrayOf(0, pixelCount / 2, pixelCount - 1).all { index ->
                pixels.getInt(index * 4L) and OpaqueAlphaMask == OpaqueAlphaMask
            }
            if (alreadyOpaque) return
            val buffer = pixels.getByteBuffer(0L, pixelCount * 4L).order(ByteOrder.nativeOrder()).asIntBuffer()
            for (index in 0 until pixelCount) {
                buffer.put(index, buffer.get(index) or OpaqueAlphaMask)
            }
        }
    }

    private companion object {
        /** sizeof(mpv_render_param) on 64-bit Windows: a 4-byte type, padding, then an 8-byte pointer. */
        const val RenderParamSize = 16L

        fun nativeString(value: String): Memory {
            val bytes = value.toByteArray(Charsets.US_ASCII)
            return Memory(bytes.size + 1L).apply {
                write(0L, bytes, 0, bytes.size)
                setByte(bytes.size.toLong(), 0)
            }
        }
    }
}
