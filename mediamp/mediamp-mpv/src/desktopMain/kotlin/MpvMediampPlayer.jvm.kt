/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.Image
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.internal.Platform
import org.openani.mediamp.internal.currentPlatform
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.coroutines.CoroutineContext

@kotlin.OptIn(InternalMediampApi::class)
actual class MpvMediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext,
    configDir: String? = null,
) : AbstractMediampPlayer<MpvMediampPlayer.MPVPlayerData>(parentCoroutineContext) {
    class MPVPlayerData(mediaData: MediaData) : Data(mediaData)

    private val handle by lazy { MPVHandle(context) }

    var currentSize: Size? = null
        @InternalMediampApi set

    internal var backendTexture: BackendTexture? = null
    internal var image: Image? = null

    private var initialized = false

    private val eventListener = object : EventListener {
        override fun onPropertyChange(name: String) {
        }

        override fun onPropertyChange(name: String, value: Boolean) {
            when (name) {
                "pause" -> playbackState.value =
                    if (value) PlaybackState.PAUSED else PlaybackState.PLAYING

                "paused-for-cache" -> playbackState.value =
                    if (value) PlaybackState.PAUSED_BUFFERING else PlaybackState.PLAYING

            }
        }

        override fun onPropertyChange(name: String, value: Long) {
            when (name) {
                "time-pos/full" -> currentPositionMillis.value = value * 1000
                "duration/full" -> mediaProperties.value =
                    if (mediaProperties.value == null) MediaProperties(null, value * 1000)
                    else mediaProperties.value?.copy(durationMillis = value * 1000)
            }
        }

        override fun onPropertyChange(name: String, value: Double) {
        }

        override fun onPropertyChange(name: String, value: String) {
            when (name) {
                "media-title" -> mediaProperties.value =
                    if (mediaProperties.value == null) MediaProperties(value, -1)
                    else mediaProperties.value?.copy(title = value)
            }
        }

    }

    override val impl: MPVHandle get() = handle

    override val currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(0L)

    override val mediaProperties: MutableStateFlow<MediaProperties?> = MutableStateFlow(null)

    override val features: PlayerFeatures = buildPlayerFeatures { }

    override fun getCurrentMediaProperties(): MediaProperties? {
        return mediaProperties.value
    }

    override fun getCurrentPlaybackState(): PlaybackState {
        return playbackState.value
    }

    override fun getCurrentPositionMillis(): Long {
        return currentPositionMillis.value
    }

    @InternalMediampApi
    fun createRenderContext(devicePtr: Long, contextPtr: Long, drawablePtr: Long = 0L): Boolean {
        return if (initialized) createRenderContext(handle.ptr, devicePtr, contextPtr, drawablePtr) else false
    }

    @InternalMediampApi
    fun releaseRenderContext(): Boolean {
        return if (initialized) destroyRenderContext(handle.ptr) else false
    }

    @InternalMediampApi
    fun createTexture(width: Int, height: Int): Int {
        return if (initialized) createTexture(handle.ptr, width, height) else 0
    }

    @InternalMediampApi
    fun releaseTexture(): Boolean {
        return if (initialized) releaseTexture(handle.ptr) else false
    }

    @InternalMediampApi
    fun renderFrame(): Boolean {
        return if (initialized) renderFrameToTexture(handle.ptr) else false
    }

    @InternalMediampApi
    fun debugRenderSolid(red: Float, green: Float, blue: Float, alpha: Float): Boolean {
        return if (initialized) debugRenderSolid(handle.ptr, red, green, blue, alpha) else false
    }

    @InternalMediampApi
    fun readTextureStats(): String {
        return if (initialized) readTextureStats(handle.ptr) else ""
    }

    init {
        handle.setEventListener(eventListener)

        if (configDir != null) {
            handle.option("config-dir", configDir)
        }
        handle.option("profile", "fast")
        handle.option("input-default-bindings", "yes")

        val cacheMegs = if (limitDemuxer()) 32 else 64
        handle.option("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        handle.option("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
        handle.option("vd-lavc-film-grain", "cpu")

        initialize(0L)
    }

    @InternalMediampApi
    fun initialize(hwnd: Long = 0L): Boolean {
        if (initialized) return true
        println("MPV_INIT start hwnd=$hwnd")

        var hardwareDecoderCodecs = "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1"

        when (currentPlatform()) {
            is Platform.Android -> {
                handle.option("gpu-context", "android")
                handle.option("opengl-es", "yes")
                handle.option("ao", "audiotrack,opensles")
                handle.option("vo", "gpu-next")
            }

            is Platform.Windows -> {
                handle.option("ao", "wasapi")
                handle.option("opengl-es", "no")
                handle.option("vo", "libmpv")
                hardwareDecoderCodecs = "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1"
            }

            is Platform.MacOS -> {
                handle.option("gpu-context", "macvk")
                handle.option("ao", "avfoundation")
                handle.option("vo", "libmpv")
            }

            is Platform.Linux -> {
                handle.option("ao", "pipewire,pulseaudio,alsa")
                handle.option("vo", "libmpv")
                handle.option("hwdec-extra-hw-frames", "16")

                hardwareDecoderCodecs = "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1"
                val forceLinuxSoftwareDecode =
                    System.getProperty("nuvio.mpv.diagnostic.hwdec") == null &&
                        System.getenv("NUVIO_MPV_DIAGNOSTIC_HWDEC") == null
                if (forceLinuxSoftwareDecode) {
                    handle.option("hwdec", "no")
                }
            }

            else -> {}
        }

        val defaultHwdec = when {
            currentPlatform() is Platform.Linux -> "no"
            currentPlatform() is Platform.Windows -> "d3d11va-copy"
            else -> "auto"
        }
        handle.option("hwdec", defaultHwdec)
        handle.option("hwdec-codecs", hardwareDecoderCodecs)

        val initResult = handle.initialize()
        println("MPV_INIT handle.initialize() = $initResult")

        handle.option("save-position-on-quit", "no")
        handle.option("force-window", "no")
        handle.option("idle", "yes")
        handle.option("keep-open", "always")

        handle.observeProperty("time-pos/full", MPVFormat.MPV_FORMAT_INT64)
        handle.observeProperty("duration/full", MPVFormat.MPV_FORMAT_INT64)
        handle.observeProperty("pause", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("paused-for-cache", MPVFormat.MPV_FORMAT_FLAG)
        handle.observeProperty("speed", MPVFormat.MPV_FORMAT_STRING)

        handle.observeProperty("media-title", MPVFormat.MPV_FORMAT_STRING)
        handle.observeProperty("metadata", MPVFormat.MPV_FORMAT_NONE)
        handle.observeProperty("hwdec-current", MPVFormat.MPV_FORMAT_NONE)

        initialized = true
        return true
    }

    @InternalMediampApi
    fun setWid(hwnd: Long): Boolean {
        if (!initialized || hwnd == 0L) return false

        // Set wid via command (works during playback)
        val cmdResult = handle.command("set", "wid", hwnd.toString())
        println("MPV_WID command(set,wid,$hwnd)=$cmdResult")
        if (cmdResult) {
            // Force VO reconfiguration to pick up the new wid by
            // setting vo to its current value.
            val currentVo = runCatching { handle.getPropertyString("vo") }.getOrNull()
            if (currentVo != null && currentVo.isNotBlank()) {
                handle.command("set", "vo", currentVo)
            }
            return true
        }

        // Fallback: try property set
        val result = handle.setPropertyString("wid", hwnd.toString())
        println("MPV_WID setPropertyString(wid,$hwnd)=$result")
        if (!result) {
            val optResult = handle.option("wid", hwnd.toString())
            println("MPV_WID option(wid,$hwnd)=$optResult (after init)")
        } else {
            val currentVo = runCatching { handle.getPropertyString("vo") }.getOrNull()
            if (currentVo != null && currentVo.isNotBlank()) {
                handle.command("set", "vo", currentVo)
            }
        }
        return result
    }

    @InternalMediampApi
    fun attachRenderSurface(surface: Any): Boolean {
        return attachSurface(handle.ptr, surface)
    }

    @InternalMediampApi
    fun detachRenderSurface(): Boolean {
        return detachSurface(handle.ptr)
    }

    override suspend fun setMediaDataImpl(data: MediaData): MPVPlayerData = when (data) {
        is UriMediaData -> {
            val headers = data.headers

            // 清除播放列表
            handle.command("stop")
            handle.command("playlist-clear")
            // 设置 headers 和 ua
            handle.option(
                "user-agent",
                headers["User-Agent"]
                    ?: """Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3""",
            )
            handle.option("http-header-fields-clr", "")
            headers.forEach { (key, value) ->
                handle.option("http-header-fields", "$key: $value")
            }

            MPVPlayerData(data)
        }

        is SeekableInputMediaData -> {
            TODO()
        }
    }

    override fun resumeImpl() {
        when (playbackState.value) {
            PlaybackState.READY -> {
                val media = openResource.value ?: return
                handle.option("pause", "true")
                when (val data = media.mediaData) {
                    is UriMediaData -> {
                        handle.command("loadfile", data.uri)
                        playbackState.value = PlaybackState.PLAYING
                    }

                    is SeekableInputMediaData -> TODO()
                    else -> {} // TODO: log unsupported media type
                }
            }

            PlaybackState.PLAYING -> {
                handle.command("cycle", "pause")
            }

            else -> {} // TODO: unreachable
        }
    }

    override fun pauseImpl() {
        if (playbackState.value == PlaybackState.PAUSED) return
        handle.command("cycle", "pause")
    }

    override fun seekTo(positionMillis: Long) {
        handle.command("seek", (positionMillis / 1000L).toString(), "absolute+exact")
        currentPositionMillis.value = positionMillis
    }

    override fun skip(deltaMillis: Long) {
        handle.command("seek", (deltaMillis / 1000L).toString(), "relative+relative")
        currentPositionMillis.value += deltaMillis
    }

    override fun stopPlaybackImpl() {
        handle.command("stop")
        currentPositionMillis.value = 0L
        playbackState.value = PlaybackState.FINISHED
    }


    override fun closeImpl() {
        handle.command("stop")
        playbackState.value = PlaybackState.DESTROYED
        releaseRenderContext()
        handle.destroy()
        handle.close()
    }

    companion object {
        internal const val GL_TEXTURE_2D = 0x0DE1
        internal const val GL_RGBA8 = 0x8058

        init {
            LibraryLoader.loadLibraries()
        }
    }
}
