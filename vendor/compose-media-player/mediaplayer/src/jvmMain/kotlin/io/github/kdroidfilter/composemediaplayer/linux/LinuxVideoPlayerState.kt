package io.github.kdroidfilter.composemediaplayer.linux

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

internal val linuxLogger = TaggedLogger("LinuxVideoPlayerState")

/**
 * LinuxVideoPlayerState — JNI-based implementation using a native C GStreamer player.
 *
 * Architecture mirrors MacVideoPlayerState: coroutine-driven polling of the native
 * layer for frames, position, audio levels, and end-of-playback detection.
 */
@Stable
class LinuxVideoPlayerState : VideoPlayerState {
    // Native player pointer (AtomicLong for lock-free reads from the frame hot path)
    private val playerPtrAtomic = AtomicLong(0L)
    private val playerPtr: Long get() = playerPtrAtomic.get()

    // Serial dispatcher for frame processing
    private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val _currentFrameState = MutableStateFlow<ImageBitmap?>(null)
    internal val currentFrameState: State<ImageBitmap?> = mutableStateOf(null)

    // Double-buffered Skia bitmaps
    private var skiaBitmapWidth: Int = 0
    private var skiaBitmapHeight: Int = 0
    private var skiaBitmapA: Bitmap? = null
    private var skiaBitmapB: Bitmap? = null
    private var nextSkiaBitmapA: Boolean = true

    // Surface display size (pixels) for output scaling
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val isResizing = AtomicBoolean(false)
    private var resizeJob: Job? = null

    // Background worker scopes and jobs
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var frameUpdateJob: Job? = null
    private var bufferingCheckJob: Job? = null
    private var uiUpdateJob: Job? = null

    // State tracking
    private var lastFrameUpdateTime: Long = 0
    private var seekInProgress = false
    private var targetSeekTime: Double? = null
    private val playbackCompletion = LinuxPlaybackCompletion()
    private val playbackResumeCoordinator = LinuxPlaybackResumeCoordinator(playbackCompletion)

    // Frame rate from native layer
    private var captureFrameRate: Float = 0.0f

    // UI State
    override var hasMedia: Boolean by mutableStateOf(false)
    override var isPlaying: Boolean by mutableStateOf(false)
    override var sliderPos: Float by mutableStateOf(0.0f)
    override var userDragging: Boolean by mutableStateOf(false)
    override var loop: Boolean by mutableStateOf(false)
    override var isLoading: Boolean by mutableStateOf(false)
    override var onPlaybackEnded: (() -> Unit)? = null
    override var onRestart: (() -> Unit)? = null
    override var error: VideoPlayerError? by mutableStateOf(null)
    override var subtitlesEnabled: Boolean by mutableStateOf(false)
    override var currentSubtitleTrack: SubtitleTrack? by mutableStateOf(null)
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableListOf()
    override var subtitleTextStyle: TextStyle by mutableStateOf(
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
    )
    override var subtitleBackgroundColor: Color by mutableStateOf(Color.Black.copy(alpha = 0.5f))
    override val metadata: VideoMetadata = VideoMetadata()
    override var isFullscreen: Boolean by mutableStateOf(false)
    private var lastUri: String? = null

    private val _positionText = mutableStateOf("00:00")
    override val positionText: String get() = _positionText.value

    private val _durationText = mutableStateOf("00:00")
    override val durationText: String get() = _durationText.value

    override val currentTime: Double
        get() =
            runBlocking {
                if (hasMedia) getPositionSafely() else 0.0
            }

    override val duration: Double
        get() =
            runBlocking {
                if (hasMedia) getDurationSafely() else 0.0
            }

    private val _aspectRatio = mutableStateOf(16f / 9f)
    override val aspectRatio: Float get() = _aspectRatio.value

    // Volume
    private val _volumeState = mutableStateOf(1.0f)
    override var volume: Float
        get() = _volumeState.value
        set(value) {
            val newValue = value.coerceIn(0f, 1f)
            if (_volumeState.value != newValue) {
                _volumeState.value = newValue
                ioScope.launch { applyVolume() }
            }
        }

    // Playback speed
    private val _playbackSpeedState = mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeedState.value
        set(value) {
            val newValue = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            if (_playbackSpeedState.value != newValue) {
                _playbackSpeedState.value = newValue
                ioScope.launch { applyPlaybackSpeed() }
            }
        }

    private val updateInterval: Long
        get() =
            if (captureFrameRate > 0) {
                (1000.0f / captureFrameRate).toLong()
            } else {
                33L // ~30fps default
            }

    private val bufferingCheckInterval = 200L
    private val bufferingTimeoutThreshold = 500L

    init {
        linuxLogger.d { "Initializing Linux video player (JNI)" }
        ioScope.launch {
            initPlayer()
            startUIUpdateJob()
        }
    }

    @OptIn(FlowPreview::class)
    private fun startUIUpdateJob() {
        uiUpdateJob?.cancel()
        uiUpdateJob =
            ioScope.launch {
                _currentFrameState.debounce(1).collect { newFrame ->
                    ensureActive()
                    withContext(Dispatchers.Main) {
                        (currentFrameState as MutableState).value = newFrame
                    }
                }
            }
    }

    private suspend fun initPlayer() =
        ioScope
            .launch {
                linuxLogger.d { "initPlayer() - Creating native player" }
                try {
                    val ptr = LinuxNativeBridge.nCreatePlayer()
                    if (ptr != 0L) {
                        playerPtrAtomic.set(ptr)
                        linuxLogger.d { "Native player created successfully" }
                        applyVolume()
                        applyPlaybackSpeed()
                    } else {
                        linuxLogger.e { "Failed to create native player" }
                        withContext(Dispatchers.Main) {
                            error = VideoPlayerError.UnknownError("Failed to create native player")
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    linuxLogger.e { "Exception in initPlayer: ${e.message}" }
                    withContext(Dispatchers.Main) {
                        error = VideoPlayerError.UnknownError("Failed to initialize player: ${e.message}")
                    }
                }
            }.join()

    private fun checkExistsIfLocalFile(uri: String): Boolean {
        val schemeDelimiter = uri.indexOf("://")
        val scheme = if (schemeDelimiter >= 0) uri.substring(0, schemeDelimiter) else ""
        return when (scheme) {
            "", "file" -> {
                val path = if (scheme == "file") uri.removePrefix("file://") else uri
                File(path).exists()
            }
            else -> true
        }
    }

    override fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState,
    ) {
        linuxLogger.d { "openUri() - Opening URI: $uri" }
        lastUri = uri

        if (!checkExistsIfLocalFile(uri)) {
            linuxLogger.e { "File does not exist: $uri" }
            setPlayerError(VideoPlayerError.SourceError("File not found: $uri"))
            return
        }

        ioScope.launch {
            playbackResumeCoordinator.reset()
            withContext(Dispatchers.Main) {
                isLoading = true
                error = null
                playbackSpeed = 1.0f
            }

            try {
                if (hasMedia) {
                    cleanupCurrentPlayback()
                }

                ensurePlayerInitialized()

                val result = openMediaUri(uri)

                if (result) {
                    // Update frame rate from native layer
                    updateFrameRateInfo()
                    updateMetadata()

                    if (surfaceWidth > 0 && surfaceHeight > 0) {
                        applyOutputScaling()
                    }

                    withContext(Dispatchers.Main) {
                        hasMedia = true
                        isLoading = false
                        isPlaying = initializeplayerState == InitialPlayerState.PLAY
                    }

                    startFrameUpdates()
                    updateFrameAsync()
                    startBufferingCheck()

                    if (isPlaying) {
                        playInBackground()
                    }
                } else {
                    linuxLogger.e { "Failed to open URI" }
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        error = VideoPlayerError.SourceError("Failed to open media source")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                linuxLogger.e { "openUri() - Exception: ${e.message}" }
                handleError(e)
            }
        }
    }

    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState,
    ) {
        openUri(file.file.path, initializeplayerState)
    }

    private suspend fun cleanupCurrentPlayback() {
        pauseInBackground()
        stopFrameUpdates()
        stopBufferingCheck()

        val ptrToDispose =
            withContext(frameDispatcher) {
                playerPtrAtomic.getAndSet(0L)
            }

        if (ptrToDispose != 0L) {
            try {
                LinuxNativeBridge.nDisposePlayer(ptrToDispose)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                linuxLogger.e { "Error disposing player: ${e.message}" }
            }
        }
    }

    private suspend fun ensurePlayerInitialized() {
        if (!playerScope.isActive) {
            playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        if (playerPtr == 0L) {
            val ptr = LinuxNativeBridge.nCreatePlayer()
            if (ptr != 0L) {
                if (!playerPtrAtomic.compareAndSet(0L, ptr)) {
                    LinuxNativeBridge.nDisposePlayer(ptr)
                } else {
                    applyVolume()
                    applyPlaybackSpeed()
                }
            } else {
                throw IllegalStateException("Failed to create native player")
            }
        }
    }

    private suspend fun openMediaUri(uri: String): Boolean {
        val ptr = playerPtr
        if (ptr == 0L) return false

        if (!checkExistsIfLocalFile(uri)) {
            setPlayerError(VideoPlayerError.SourceError("File not found: $uri"))
            return false
        }

        return try {
            LinuxNativeBridge.nOpenUri(ptr, uri)
            pollDimensionsUntilReady(ptr)
            updateMetadata()
            true
        } catch (e: Exception) {
            linuxLogger.e { "Failed to open URI: ${e.message}" }
            setPlayerError(VideoPlayerError.SourceError("Error opening media: ${e.message}"))
            false
        }
    }

    private suspend fun pollDimensionsUntilReady(
        ptr: Long,
        maxAttempts: Int = 20,
    ) {
        for (attempt in 1..maxAttempts) {
            val width = LinuxNativeBridge.nGetFrameWidth(ptr)
            val height = LinuxNativeBridge.nGetFrameHeight(ptr)
            if (width > 0 && height > 0) {
                linuxLogger.d { "Dimensions validated (w=$width, h=$height) after $attempt attempts" }
                return
            }
            linuxLogger.d { "Dimensions not ready yet (attempt $attempt/$maxAttempts)" }
            delay(250)
        }
        linuxLogger.e { "Unable to retrieve valid dimensions after $maxAttempts attempts" }
    }

    private suspend fun updateFrameRateInfo() {
        val ptr = playerPtr
        if (ptr == 0L) return
        try {
            captureFrameRate = LinuxNativeBridge.nGetFrameRate(ptr)
            linuxLogger.d { "Frame rate: $captureFrameRate" }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error updating frame rate: ${e.message}" }
        }
    }

    private suspend fun updateMetadata() {
        val ptr = playerPtr
        if (ptr == 0L) return

        try {
            val width = LinuxNativeBridge.nGetFrameWidth(ptr)
            val height = LinuxNativeBridge.nGetFrameHeight(ptr)
            val duration = (LinuxNativeBridge.nGetVideoDuration(ptr) * 1000).toLong()
            val frameRate = LinuxNativeBridge.nGetFrameRate(ptr)
            val newAspectRatio =
                if (width > 0 && height > 0) {
                    width.toFloat() / height.toFloat()
                } else {
                    _aspectRatio.value
                }

            val title = LinuxNativeBridge.nGetVideoTitle(ptr)
            val bitrate = LinuxNativeBridge.nGetVideoBitrate(ptr)
            val mimeType = LinuxNativeBridge.nGetVideoMimeType(ptr)
            val audioChannels = LinuxNativeBridge.nGetAudioChannels(ptr)
            val audioSampleRate = LinuxNativeBridge.nGetAudioSampleRate(ptr)

            withContext(Dispatchers.Main) {
                metadata.duration = duration
                metadata.width = width
                metadata.height = height
                metadata.frameRate = frameRate
                metadata.title = title
                metadata.bitrate = bitrate
                metadata.mimeType = mimeType
                metadata.audioChannels = if (audioChannels == 0) null else audioChannels
                metadata.audioSampleRate = if (audioSampleRate == 0) null else audioSampleRate
                _aspectRatio.value = newAspectRatio
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error updating metadata: ${e.message}" }
        }
    }

    // --- Frame update loop ---

    private fun startFrameUpdates() {
        stopFrameUpdates()
        val playbackGeneration = playbackCompletion.captureGeneration()
        frameUpdateJob =
            ioScope.launch {
                while (isActive) {
                    ensureActive()
                    updateFrameAsync()
                    if (!userDragging) {
                        updatePositionAsync(playbackGeneration)
                    }
                    delay(updateInterval)
                }
            }
    }

    private fun stopFrameUpdates() {
        frameUpdateJob?.cancel()
        frameUpdateJob = null
    }

    private fun startBufferingCheck() {
        stopBufferingCheck()
        bufferingCheckJob =
            ioScope.launch {
                while (isActive) {
                    ensureActive()
                    checkBufferingState()
                    delay(bufferingCheckInterval)
                }
            }
    }

    private suspend fun checkBufferingState() {
        if (isPlaying && !isLoading) {
            val timeSinceLastFrame = System.currentTimeMillis() - lastFrameUpdateTime
            if (timeSinceLastFrame > bufferingTimeoutThreshold) {
                withContext(Dispatchers.Main) { isLoading = true }
            }
        }
    }

    private fun stopBufferingCheck() {
        bufferingCheckJob?.cancel()
        bufferingCheckJob = null
    }

    private suspend fun updateFrameAsync() {
        withContext(frameDispatcher) {
            try {
                val ptr = playerPtr
                if (ptr == 0L) return@withContext

                val width = LinuxNativeBridge.nGetFrameWidth(ptr)
                val height = LinuxNativeBridge.nGetFrameHeight(ptr)
                if (width <= 0 || height <= 0) return@withContext

                val frameAddress = LinuxNativeBridge.nGetLatestFrameAddress(ptr)
                if (frameAddress == 0L) return@withContext

                val pixelCount = width * height
                val frameSizeBytes = pixelCount.toLong() * 4L
                var framePublished = false

                withContext(Dispatchers.Default) {
                    val srcBuf =
                        LinuxNativeBridge.nWrapPointer(frameAddress, frameSizeBytes)
                            ?: return@withContext

                    // Allocate/reuse double-buffered bitmaps
                    if (skiaBitmapA == null || skiaBitmapWidth != width || skiaBitmapHeight != height) {
                        skiaBitmapA?.close()
                        skiaBitmapB?.close()

                        val imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                        skiaBitmapA = Bitmap().apply { allocPixels(imageInfo) }
                        skiaBitmapB = Bitmap().apply { allocPixels(imageInfo) }
                        skiaBitmapWidth = width
                        skiaBitmapHeight = height
                        nextSkiaBitmapA = true
                    }

                    val targetBitmap = if (nextSkiaBitmapA) skiaBitmapA!! else skiaBitmapB!!
                    nextSkiaBitmapA = !nextSkiaBitmapA

                    val pixmap = targetBitmap.peekPixels() ?: return@withContext
                    val pixelsAddr = pixmap.addr
                    if (pixelsAddr == 0L) return@withContext

                    // Native-to-native copy: frame buffer -> Skia bitmap pixels
                    srcBuf.rewind()
                    val destRowBytes = pixmap.rowBytes
                    val destSizeBytes = destRowBytes.toLong() * height.toLong()
                    val destBuf =
                        LinuxNativeBridge.nWrapPointer(pixelsAddr, destSizeBytes)
                            ?: return@withContext
                    copyBgraFrame(srcBuf, destBuf, width, height, destRowBytes)

                    _currentFrameState.value = targetBitmap.asComposeImageBitmap()
                    framePublished = true
                }

                if (framePublished) {
                    lastFrameUpdateTime = System.currentTimeMillis()
                    if (isLoading && !seekInProgress) {
                        withContext(Dispatchers.Main) { isLoading = false }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                linuxLogger.e { "updateFrameAsync() - Exception: ${e.message}" }
            }
        }
    }

    private suspend fun updatePositionAsync(playbackGeneration: Long) {
        if (!hasMedia || userDragging) return
        try {
            val duration = getDurationSafely()
            if (duration <= 0) return

            val current = getPositionSafely()

            withContext(Dispatchers.Main) {
                _positionText.value = formatTime(current)
                _durationText.value = formatTime(duration)
            }

            if (seekInProgress && targetSeekTime != null) {
                if (abs(current - targetSeekTime!!) < 0.3) {
                    seekInProgress = false
                    targetSeekTime = null
                    withContext(Dispatchers.Main) { isLoading = false }
                }
            } else {
                val newSliderPos = (current / duration * 1000).toFloat().coerceIn(0f, 1000f)
                withContext(Dispatchers.Main) { sliderPos = newSliderPos }
            }

            checkLoopingAsync(current, duration, playbackGeneration)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error in updatePositionAsync: ${e.message}" }
        }
    }

    private suspend fun checkLoopingAsync(
        current: Double,
        duration: Double,
        playbackGeneration: Long,
    ) {
        // Preserve the existing looping path. Durable completion ownership is only
        // needed for non-looping replay after the one-shot native EOS signal.
        if (loop) {
            val ptr = playerPtr
            val ended = ptr != 0L && LinuxNativeBridge.nConsumeDidPlayToEnd(ptr)
            if (!ended && (duration <= 0 || current < duration - 0.5)) return
            seekToAsync(0f)
            onRestart?.invoke()
            return
        }

        val reachedEnd =
            playbackResumeCoordinator.markEndedIfConsumed(playbackGeneration) {
                val ptr = playerPtr
                ptr != 0L && LinuxNativeBridge.nConsumeDidPlayToEnd(ptr)
            }
        if (!reachedEnd) return

        val pausedCurrentPlayback =
            playbackResumeCoordinator.runIfCurrent(playbackGeneration) {
                val ptr = playerPtr
                if (ptr != 0L) LinuxNativeBridge.nPause(ptr)
            }
        if (!pausedCurrentPlayback) return

        withContext(Dispatchers.Main) {
            if (playbackCompletion.isCurrent(playbackGeneration)) {
                isPlaying = false
                isLoading = false
            }
        }
        playbackResumeCoordinator.runIfCurrent(playbackGeneration) {
            stopFrameUpdates()
            stopBufferingCheck()
            onPlaybackEnded?.invoke()
        }
    }

    // --- Playback controls ---

    override fun play() {
        ioScope.launch {
            if (!hasMedia && lastUri != null) {
                openUri(lastUri!!)
            } else if (hasMedia) {
                playInBackground()
            } else {
                withContext(Dispatchers.Main) {
                    isPlaying = false
                    isLoading = false
                }
            }
        }
    }

    private suspend fun playInBackground() {
        val ptr = playerPtr
        if (ptr == 0L) return
        try {
            playbackResumeCoordinator.resume(
                seekToStart = { LinuxNativeBridge.nSeekTo(ptr, 0.0) },
                play = { LinuxNativeBridge.nPlay(ptr) },
            )
            withContext(Dispatchers.Main) { isPlaying = true }
            startFrameUpdates()
            startBufferingCheck()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error in playInBackground: ${e.message}" }
            handleError(e)
        }
    }

    override fun pause() {
        ioScope.launch { pauseInBackground() }
    }

    private suspend fun pauseInBackground() {
        val ptr = playerPtr
        if (ptr == 0L) return
        try {
            playbackResumeCoordinator.runCommand { LinuxNativeBridge.nPause(ptr) }
            withContext(Dispatchers.Main) {
                isPlaying = false
                isLoading = false
            }
            updateFrameAsync()
            stopFrameUpdates()
            stopBufferingCheck()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error in pauseInBackground: ${e.message}" }
        }
    }

    override fun stop() {
        ioScope.launch {
            pauseInBackground()
            if (hasMedia) seekToAsync(0f)
            resetState()
        }
    }

    override fun seekTo(value: Float) {
        ioScope.launch {
            delay(10) // Coalesce rapid seek events
            seekToAsync(value)
        }
    }

    private suspend fun seekToAsync(value: Float) {
        withContext(Dispatchers.Main) { isLoading = true }

        try {
            val duration = getDurationSafely()
            if (duration <= 0) {
                withContext(Dispatchers.Main) { isLoading = false }
                return
            }

            val seekTime = ((value / 1000f) * duration.toFloat()).coerceIn(0f, duration.toFloat())

            withContext(Dispatchers.Main) {
                seekInProgress = true
                targetSeekTime = seekTime.toDouble()
                sliderPos = value
            }

            lastFrameUpdateTime = System.currentTimeMillis()

            val ptr = playerPtr
            if (ptr == 0L) return
            playbackResumeCoordinator.resetAndRun {
                LinuxNativeBridge.nSeekTo(ptr, seekTime.toDouble())
                if (isPlaying) {
                    LinuxNativeBridge.nPlay(ptr)
                }
            }

            if (isPlaying) {
                startFrameUpdates()
                delay(10)
                updateFrameAsync()
                ioScope.launch {
                    delay(300)
                    if (seekInProgress) {
                        seekInProgress = false
                        targetSeekTime = null
                        withContext(Dispatchers.Main) { isLoading = false }
                    }
                }
            } else {
                delay(50)
                updateFrameAsync()
                seekInProgress = false
                targetSeekTime = null
                withContext(Dispatchers.Main) { isLoading = false }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error in seekToAsync: ${e.message}" }
            withContext(Dispatchers.Main) {
                isLoading = false
                seekInProgress = false
                targetSeekTime = null
            }
        }
    }

    override fun clearError() {
        // Main.immediate: when already on the main thread (Compose effects call
        // this from it) run inline instead of dispatching onto the thread that
        // runBlocking just parked - plain Dispatchers.Main deadlocks the EDT.
        runBlocking {
            withContext(Dispatchers.Main.immediate) { error = null }
        }
    }

    override fun toggleFullscreen() {
        isFullscreen = !isFullscreen
    }

    override fun dispose() {
        stopFrameUpdates()
        stopBufferingCheck()
        uiUpdateJob?.cancel()
        playerScope.cancel()

        // Clear the pointer atomically so no background task can use it
        val ptrToDispose = playerPtrAtomic.getAndSet(0L)

        // Native cleanup on a background thread to avoid blocking the UI.
        Thread {
            try {
                skiaBitmapA?.close()
                skiaBitmapB?.close()
                skiaBitmapA = null
                skiaBitmapB = null
                skiaBitmapWidth = 0
                skiaBitmapHeight = 0
                nextSkiaBitmapA = true
            } catch (e: Exception) {
                linuxLogger.e { "Error releasing bitmaps: ${e.message}" }
            }

            if (ptrToDispose != 0L) {
                try {
                    LinuxNativeBridge.nDisposePlayer(ptrToDispose)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    linuxLogger.e { "Error disposing player: ${e.message}" }
                }
            }
        }.start()

        ioScope.cancel()
    }

    // --- Subtitle stubs ---

    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        ioScope.launch {
            withContext(Dispatchers.Main) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
        }
    }

    override fun disableSubtitles() {
        ioScope.launch {
            withContext(Dispatchers.Main) {
                subtitlesEnabled = false
                currentSubtitleTrack = null
            }
        }
    }

    // --- Output scaling ---

    fun onResized(
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        if (width == surfaceWidth && height == surfaceHeight) return

        surfaceWidth = width
        surfaceHeight = height

        isResizing.set(true)
        resizeJob?.cancel()
        resizeJob =
            ioScope.launch {
                delay(120)
                try {
                    applyOutputScaling()
                } finally {
                    isResizing.set(false)
                }
            }
    }

    private suspend fun applyOutputScaling() {
        val sw = surfaceWidth
        val sh = surfaceHeight
        if (sw <= 0 || sh <= 0) return
        val ptr = playerPtr
        if (ptr == 0L) return

        // Compute output dimensions that fit within the surface while preserving
        // the video's native aspect ratio. Passing the raw surface size would let
        // GStreamer stretch the frame to an arbitrary ratio.
        val videoRatio = _aspectRatio.value
        val surfaceRatio = sw.toFloat() / sh.toFloat()

        val (outW, outH) =
            if (videoRatio > surfaceRatio) {
                // Video is wider than surface → fit to width
                sw to (sw / videoRatio).toInt().coerceAtLeast(1)
            } else {
                // Video is taller than surface → fit to height
                (sh * videoRatio).toInt().coerceAtLeast(1) to sh
            }

        LinuxNativeBridge.nSetOutputSize(ptr, outW, outH)
    }

    // --- Internal helpers ---

    private suspend fun resetState() {
        playbackResumeCoordinator.reset()
        withContext(Dispatchers.Main) {
            hasMedia = false
            isPlaying = false
            isLoading = false
            _positionText.value = "00:00"
            _durationText.value = "00:00"
            _aspectRatio.value = 16f / 9f
            error = null
        }
        _currentFrameState.value = null
    }

    private fun setPlayerError(error: VideoPlayerError) {
        runBlocking {
            withContext(Dispatchers.Main) {
                isLoading = false
                this@LinuxVideoPlayerState.error = error
            }
        }
    }

    private suspend fun handleError(e: Exception) {
        withContext(Dispatchers.Main) {
            isLoading = false
            error = VideoPlayerError.SourceError("Error: ${e.message}")
        }
    }

    private suspend fun getPositionSafely(): Double {
        val ptr = playerPtr
        if (ptr == 0L) return 0.0
        return try {
            LinuxNativeBridge.nGetCurrentTime(ptr)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            0.0
        }
    }

    private suspend fun getDurationSafely(): Double {
        val ptr = playerPtr
        if (ptr == 0L) return 0.0
        return try {
            LinuxNativeBridge.nGetVideoDuration(ptr)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            0.0
        }
    }

    private suspend fun applyVolume() {
        val ptr = playerPtr
        if (ptr != 0L) {
            try {
                LinuxNativeBridge.nSetVolume(ptr, _volumeState.value)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private suspend fun applyPlaybackSpeed() {
        val ptr = playerPtr
        if (ptr != 0L) {
            try {
                LinuxNativeBridge.nSetPlaybackSpeed(ptr, _playbackSpeedState.value)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }
}
