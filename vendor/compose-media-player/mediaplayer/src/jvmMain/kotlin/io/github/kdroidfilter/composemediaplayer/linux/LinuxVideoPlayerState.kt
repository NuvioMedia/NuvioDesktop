package io.github.kdroidfilter.composemediaplayer.linux

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

internal val linuxLogger = TaggedLogger("LinuxVideoPlayerState")

private data class LinuxNativeMetadataSnapshot(
    val width: Int,
    val height: Int,
    val durationMillis: Long,
    val frameRate: Float,
    val title: String?,
    val bitrate: Long,
    val mimeType: String?,
    val audioChannels: Int,
    val audioSampleRate: Int,
)

private data class LinuxTaggedFrame(
    val sourceGeneration: Long,
    val image: ImageBitmap?,
)

internal class LinuxSourcePlaybackContext(
    val sourceGeneration: Long,
    initialState: InitialPlayerState = InitialPlayerState.PAUSE,
    val completion: LinuxPlaybackCompletion = LinuxPlaybackCompletion(),
    private val predecessorAwaitRegisteredForTest: ((CommandTicket) -> Unit)? = null,
) {
    data class Intent(
        val desiredPlaying: Boolean,
        val serial: Long,
    )

    data class CommandTicket(
        val intent: Intent,
        val predecessor: Deferred<Unit>,
        val publicationPredecessor: Deferred<Unit>,
        val completion: CompletableDeferred<Unit>,
        val requiresCurrentIntent: Boolean,
        val claimsPublication: Boolean,
    ) {
        private val executionClaimed = AtomicBoolean(false)

        fun claimExecution(): Boolean = executionClaimed.compareAndSet(false, true)
    }

    data class Request(
        val intent: Intent,
        val opening: Boolean,
        val command: CommandTicket?,
    )

    private val intentCommandLock = Any()
    private val publicationCallbackLock = Any()
    private var desiredPlaying = initialState == InitialPlayerState.PLAY
    private var intentSerial = 0L
    private var opening = true
    private val openingBarrier = CompletableDeferred<Unit>()
    private var commandTail: Deferred<Unit> = openingBarrier
    private var latestPublicationOwner: Deferred<Unit> = commandTail
    val resume = LinuxPlaybackResumeCoordinator(completion)

    fun requestPlaying(value: Boolean): Request =
        synchronized(publicationCallbackLock) {
            synchronized(intentCommandLock) {
                check(intentSerial != Long.MAX_VALUE) { "Playback intent serial exhausted" }
                intentSerial += 1L
                desiredPlaying = value
                val intent = Intent(value, intentSerial)
                Request(intent, opening, if (opening) null else reserveCommandLocked(intent))
            }
        }

    /** Atomically transfers desired-state responsibility from opening to controls. */
    fun finishOpening(reserveAutoplay: Boolean = true): Request {
        val request =
            synchronized(publicationCallbackLock) {
                synchronized(intentCommandLock) {
                    val intent = Intent(desiredPlaying, intentSerial)
                    if (!opening) {
                        Request(intent, opening = false, command = null)
                    } else {
                        opening = false
                        Request(
                            intent,
                            opening = false,
                            command =
                                if (reserveAutoplay && intent.desiredPlaying) reserveCommandLocked(intent) else null,
                        )
                    }
                }
            }
        openingBarrier.complete(Unit)
        return request
    }

    fun reserveCommand(
        requiresCurrentIntent: Boolean = true,
        claimsPublication: Boolean = true,
    ): CommandTicket =
        synchronized(publicationCallbackLock) {
            synchronized(intentCommandLock) {
                reserveCommandLocked(Intent(desiredPlaying, intentSerial), requiresCurrentIntent, claimsPublication)
            }
        }

    fun reservePublicationCommandIfUnchanged(observed: CommandTicket): CommandTicket? =
        synchronized(publicationCallbackLock) publication@{
            synchronized(intentCommandLock) {
                if (latestPublicationOwner !== observed.publicationPredecessor) return@publication null
                reserveCommandLocked(
                    Intent(desiredPlaying, intentSerial),
                    requiresCurrentIntent = false,
                    claimsPublication = true,
                )
            }
        }


    fun invokeCallbackIfLatest(
        ticket: CommandTicket,
        callback: () -> Boolean,
    ): Boolean =
        synchronized(publicationCallbackLock) {
            if (!ownsLatestPublication(ticket)) false else callback()
        }

    fun invokeCallbackIfLatestCommand(
        ticket: CommandTicket,
        callback: () -> Boolean,
    ): Boolean =
        synchronized(publicationCallbackLock) {
            if (!isLatestCommand(ticket)) false else callback()
        }

    suspend fun <T> runCommand(
        ticket: CommandTicket,
        terminalizeLatestPublication: suspend (CommandTicket) -> Unit = {},
        block: suspend () -> T,
    ): T? {
        if (!ticket.claimExecution()) return null
        return try {
            awaitPredecessor(ticket)
            if (!ticket.requiresCurrentIntent || isCurrent(ticket.intent)) block() else null
        } finally {
            // Cancellation cannot release a successor past an unfinished predecessor.
            // Settlement remains non-cancellable, but no bookkeeping lock is held while awaiting.
            withContext(NonCancellable) {
                try {
                    ticket.predecessor.await()
                } finally {
                    try {
                        if (ticket.claimsPublication) terminalizeLatestPublication(ticket)
                    } finally {
                        ticket.completion.complete(Unit)
                    }
                }
            }
        }
    }

    fun settleIfExecutionNeverStarted(
        ticket: CommandTicket,
        terminalizeLatestPublication: (CommandTicket) -> Unit = {},
    ) {
        if (!ticket.claimExecution()) return
        ticket.predecessor.invokeOnCompletion {
            try {
                if (ticket.claimsPublication) terminalizeLatestPublication(ticket)
            } finally {
                ticket.completion.complete(Unit)
            }
        }
    }

    private suspend fun awaitPredecessor(ticket: CommandTicket) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val registration =
                ticket.predecessor.invokeOnCompletion { failure ->
                    continuation.resumeWith(
                        if (failure == null) Result.success(Unit) else Result.failure(failure),
                    )
                }
            continuation.invokeOnCancellation { registration.dispose() }
            if (!ticket.predecessor.isCompleted && continuation.isActive) {
                predecessorAwaitRegisteredForTest?.invoke(ticket)
            }
        }
    }

    fun isCurrent(intent: Intent): Boolean = synchronized(intentCommandLock) { isCurrentLocked(intent) }

    fun commitIfCurrent(
        intent: Intent,
        commit: () -> Boolean,
    ): Boolean = synchronized(intentCommandLock) { isCurrentLocked(intent) && commit() }

    fun commitIfLatest(
        ticket: CommandTicket,
        commit: () -> Boolean,
    ): Boolean = synchronized(intentCommandLock) { latestPublicationOwner === ticket.completion && commit() }

    fun commitIfLatestCommand(
        ticket: CommandTicket,
        commit: () -> Boolean,
    ): Boolean = synchronized(intentCommandLock) { commandTail === ticket.completion && commit() }

    fun ownsLatestPublication(ticket: CommandTicket): Boolean =
        synchronized(intentCommandLock) { latestPublicationOwner === ticket.completion }

    fun isLatestCommand(ticket: CommandTicket): Boolean =
        synchronized(intentCommandLock) { commandTail === ticket.completion }

    fun isOpening(): Boolean = synchronized(intentCommandLock) { opening }

    internal fun desiredPlayingForTest(): Boolean = synchronized(intentCommandLock) { desiredPlaying }

    private fun reserveCommandLocked(
        intent: Intent,
        requiresCurrentIntent: Boolean = true,
        claimsPublication: Boolean = true,
    ): CommandTicket {
        val completion = CompletableDeferred<Unit>()
        return CommandTicket(
            intent,
            commandTail,
            latestPublicationOwner,
            completion,
            requiresCurrentIntent,
            claimsPublication,
        ).also {
            commandTail = completion
            if (claimsPublication) latestPublicationOwner = completion
        }
    }

    private fun isCurrentLocked(intent: Intent): Boolean =
        intent.serial == intentSerial && intent.desiredPlaying == desiredPlaying
}

internal fun invokeLifecycleCallbackIfLatestCommand(
    lifecycle: LinuxPlayerLifecycle,
    sourceGeneration: Long,
    playback: LinuxSourcePlaybackContext,
    ticket: LinuxSourcePlaybackContext.CommandTicket,
    callback: () -> Unit,
): Boolean {
    var invoked = false
    val admitted =
        lifecycle.invokeCallback(sourceGeneration) {
            invoked =
                playback.invokeCallbackIfLatestCommand(ticket) {
                    callback()
                    true
                }
        }
    return admitted && invoked
}

internal fun invokeLifecycleCallbackIfLatestPublication(
    lifecycle: LinuxPlayerLifecycle,
    sourceGeneration: Long,
    playback: LinuxSourcePlaybackContext,
    ticket: LinuxSourcePlaybackContext.CommandTicket,
    callback: () -> Unit,
): Boolean {
    var invoked = false
    val admitted =
        lifecycle.invokeCallback(sourceGeneration) {
            invoked =
                playback.invokeCallbackIfLatest(ticket) {
                    callback()
                    true
                }
        }
    return admitted && invoked
}

/**
 * LinuxVideoPlayerState — JNI-based implementation using a native C GStreamer player.
 *
 * Architecture mirrors MacVideoPlayerState: coroutine-driven polling of the native
 * layer for frames, position, audio levels, and end-of-playback detection.
 */
@Stable
class LinuxVideoPlayerState internal constructor(
    private val bridge: LinuxPlayerBridge,
    private val lifecycle: LinuxPlayerLifecycle = LinuxPlayerLifecycle(),
    private val frameDeliveryObserver: ((Long, Boolean) -> Unit)? = null,
    private val sourceReadyObserver: ((Long, Boolean) -> Unit)? = null,
    private val beforeOpeningIntentApplyForTest: ((Long, Boolean) -> Unit)? = null,
    private val frameRenderingEnabled: Boolean = true,
    private val beforeAsyncOperationForTest: (suspend (String, Long) -> Unit)? = null,
    private val asyncOperationCompletedForTest: ((String, Long) -> Unit)? = null,
    private val disposalCompletedForTest: ((Throwable?) -> Unit)? = null,
    private val jobTransitionObserverForTest: ((String, String, LinuxGenerationJobSlot.Ticket) -> Unit)? = null,
    private val beforeOpenJobCancelForTest: (() -> Unit)? = null,
    private val beforeOpenCleanupForTest: (() -> Unit)? = null,
    private val sourceOpenCompletedForTest: ((Long, Throwable?) -> Unit)? = null,
    private val initializationCompletedForTest: ((Throwable?) -> Unit)? = null,
    private val disposalWorkerStartedForTest: (() -> Unit)? = null,
    private val disposalIoRootCancelledForTest: (() -> Unit)? = null,
    private val disposalWaitingForCreationsForTest: ((Int) -> Unit)? = null,
    private val disposalOpenJobsSnapshottedForTest: ((Int) -> Unit)? = null,
    private val disposalJoiningOpenJobsForTest: ((Int) -> Unit)? = null,
    private val disposalOpenJobsJoinedForTest: ((Int) -> Unit)? = null,
    private val disposalWaitAttemptForTest: ((DisposalWaitBoundary) -> Unit)? = null,
    private val disposalJobWaitRegisteredForTest: ((DisposalWaitBoundary) -> Unit)? = null,
    private val disposalWaitInterruptedForTest: ((DisposalWaitBoundary, InterruptedException) -> Unit)? = null,
    private val afterStopPauseForTest: ((Long) -> Unit)? = null,
    private val beforeStopManagedCommitForTest: (() -> Unit)? = null,
    private val stopManagedCommitCompletedForTest: (() -> Unit)? = null,
    private val managedResourceCloseForTest: ((Int) -> Unit)? = null,
    private val managedResourcesDetachedForTest: (() -> Unit)? = null,
    private val beforePlayWorkerInstallForTest: (() -> Unit)? = null,
    private val beforeSeekWorkerInstallForTest: (() -> Unit)? = null,
    private val installationWaitingForDrainForTest: ((Long) -> Unit)? = null,
    private val commandAwaitingPredecessorForTest: ((Long) -> Unit)? = null,
    private val afterPlaybackEndedCallbackForTest: (() -> Unit)? = null,
    private val eosFinalizationCompletedForTest: (() -> Unit)? = null,
    private val updatePositionFailureForTest: ((Throwable) -> Unit)? = null,
    private val afterSeekNativeCommandForTest: (() -> Unit)? = null,
    private val beforeBufferingPublicationForTest: (() -> Unit)? = null,
    private val disposalInterruptedForTest: (() -> Unit)? = null,
    private val disposalWorkerLauncher: ((Runnable) -> CompletableFuture<Void>) = { task ->
        CompletableFuture.runAsync(task)
    },
    private val commandReservedBeforeLaunchForTest: ((String, Long) -> Unit)? = null,
    private val initialPlaybackCompletionGenerationForTest: Long? = null,
    private val awaitSeekTerminalResetForTest: (suspend () -> Unit)? = null,
    private val afterSeekTerminalResetPublicationForTest: (() -> Unit)? = null,
    private val beforeSeekSuccessCallbackDispatchForTest: (() -> Unit)? = null,
    private val beforeResizeApplyForTest: ((Int, Int) -> Unit)? = null,
    private val commandTerminalizedForTest: ((Long) -> Unit)? = null,
    private val rejectOpenRegistrationForTest: AtomicBoolean? = null,
) : VideoPlayerState {
    constructor() : this(JniLinuxPlayerBridge)

    private val initGeneration = lifecycle.beginOpen()
    private val openRequestLock = Any()
    private var openJob: Job? = null
    private val openJobs = mutableSetOf<Job>()
    private var openJobsClosed = false
    private val creationTracker = LinuxNativeCreationTracker(disposalWaitingForCreationsForTest)

    // Serial dispatcher for frame processing
    private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val _currentFrameState = MutableStateFlow(LinuxTaggedFrame(initGeneration, null))
    internal val currentFrameState: State<ImageBitmap?> = mutableStateOf(null)

    // Double-buffered Skia bitmaps
    private var skiaBitmapWidth: Int = 0
    private var skiaBitmapHeight: Int = 0
    private var skiaBitmapA: Bitmap? = null
    private var skiaBitmapB: Bitmap? = null
    private var skiaBitmapABuffer: ByteBuffer? = null
    private var skiaBitmapBBuffer: ByteBuffer? = null
    private val frameCopyInfo = IntArray(3)
    private val frameResourceGate = LinuxFrameResourceGate()
    private var nextSkiaBitmapA: Boolean = true

    // Surface display size (pixels) for output scaling
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val isResizing = AtomicBoolean(false)
    private val resizeApplyMutex = Mutex()
    private var resizeJob: Job? = null

    // Background worker scopes and jobs
    private val ioRootJob = SupervisorJob()
    private val ioScope = CoroutineScope(Dispatchers.IO + ioRootJob)
    private val frameUpdateJobs = LinuxGenerationJobSlot<Job>(Job::cancel)
    private val bufferingCheckJobs = LinuxGenerationJobSlot<Job>(Job::cancel)
    private var uiUpdateJob: Job? = null
    private val initializationJob: Job
    private val disposalFuture = AtomicReference<CompletableFuture<Unit>?>(null)

    // State tracking
    private var lastFrameUpdateTime: Long = 0
    private var seekInProgress: Boolean = false
    private var targetSeekTime: Double? = null

    private fun clearSeekState() {
        seekInProgress = false
        targetSeekTime = null
        isLoading = false
    }

    private fun clearExhaustedSourceState() {
        clearSeekState()
        hasMedia = false
        isPlaying = false
        userDragging = false
        sliderPos = 0f
        _positionText.value = "00:00"
        _durationText.value = "00:00"
        _aspectRatio.value = 16f / 9f
        error = null
        lastUri = null
        _currentFrameState.value = LinuxTaggedFrame(0L, null)
        (currentFrameState as MutableState).value = null
    }

    private suspend fun terminalizeLatestPublication(
        sourceGeneration: Long,
        playback: LinuxSourcePlaybackContext,
        command: LinuxSourcePlaybackContext.CommandTicket,
    ) {
        val terminalized =
            withContext(Dispatchers.Main.immediate) {
                playback.commitIfLatest(command) {
                    lifecycle.publish(sourceGeneration) {
                        clearSeekState()
                    }
                }
            }
        if (terminalized) commandTerminalizedForTest?.invoke(sourceGeneration)
    }

    private fun terminalizeLatestPublicationBlocking(
        sourceGeneration: Long,
        playback: LinuxSourcePlaybackContext,
        command: LinuxSourcePlaybackContext.CommandTicket,
    ) {
        runBlocking {
            terminalizeLatestPublication(sourceGeneration, playback, command)
        }
    }

    private fun launchReservedCommand(
        name: String,
        sourceGeneration: Long,
        playback: LinuxSourcePlaybackContext,
        command: LinuxSourcePlaybackContext.CommandTicket,
        body: suspend () -> Unit,
    ): Job {
        var launched: Job? = null
        try {
            commandReservedBeforeLaunchForTest?.invoke(name, sourceGeneration)
            val job = ioScope.launch { body() }
            launched = job
            job.invokeOnCompletion {
                playback.settleIfExecutionNeverStarted(command) {
                    terminalizeLatestPublicationBlocking(sourceGeneration, playback, it)
                }
            }
            return job
        } catch (failure: Throwable) {
            launched?.cancel(
                CancellationException("Command launch ownership transfer failed").apply {
                    initCause(failure)
                },
            )
            playback.settleIfExecutionNeverStarted(command) {
                terminalizeLatestPublicationBlocking(sourceGeneration, playback, it)
            }
            throw failure
        }
    }

    @Volatile
    private var playbackContext =
        LinuxSourcePlaybackContext(
            initGeneration,
            completion = LinuxPlaybackCompletion(initialPlaybackCompletionGenerationForTest ?: 0L),
            predecessorAwaitRegisteredForTest = { commandAwaitingPredecessorForTest?.invoke(initGeneration) },
        )

    private fun playbackContextFor(sourceGeneration: Long): LinuxSourcePlaybackContext? =
        playbackContext.takeIf { it.sourceGeneration == sourceGeneration }

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
    private val volumeRequestLock = Any()
    private val volumeApplyMutex = Mutex()
    private var latestVolumeRequest = 0L

    private fun reserveVolumeRequestLocked(): Long {
        check(latestVolumeRequest != Long.MAX_VALUE) { "Volume request space exhausted" }
        return ++latestVolumeRequest
    }

    override var volume: Float
        get() = _volumeState.value
        set(value) {
            val newValue = value.coerceIn(0f, 1f)
            val request =
                synchronized(volumeRequestLock) {
                    if (_volumeState.value == newValue) return
                    _volumeState.value = newValue
                    reserveVolumeRequestLocked()
                }
            val sourceGeneration = lifecycle.capture()
            ioScope.launch {
                trackedAsyncOperation("volume", sourceGeneration) {
                    applyVolume(sourceGeneration, newValue, request)
                }
            }
        }

    // Playback speed
    private val _playbackSpeedState = mutableStateOf(1.0f)
    private val speedRequestLock = Any()
    private val speedApplyMutex = Mutex()
    private var latestSpeedRequest = 0L

    private fun reserveSpeedRequestLocked(): Long {
        check(latestSpeedRequest != Long.MAX_VALUE) { "Speed request space exhausted" }
        return ++latestSpeedRequest
    }

    override var playbackSpeed: Float
        get() = _playbackSpeedState.value
        set(value) {
            val newValue = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            val request =
                synchronized(speedRequestLock) {
                    if (_playbackSpeedState.value == newValue) return
                    _playbackSpeedState.value = newValue
                    reserveSpeedRequestLocked()
                }
            val sourceGeneration = lifecycle.capture()
            ioScope.launch {
                trackedAsyncOperation("speed", sourceGeneration) {
                    applyPlaybackSpeed(sourceGeneration, newValue, request)
                }
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
        initializationJob =
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
                _currentFrameState.debounce(1).collect { taggedFrame ->
                    ensureActive()
                    val published =
                        withContext(Dispatchers.Main) {
                            lifecycle.publish(taggedFrame.sourceGeneration) {
                                (currentFrameState as MutableState).value = taggedFrame.image
                            }
                        }
                    frameDeliveryObserver?.invoke(taggedFrame.sourceGeneration, published)
                }
            }
    }

    internal fun enqueueFrameForTest(
        sourceGeneration: Long,
        image: ImageBitmap?,
    ) {
        _currentFrameState.value = LinuxTaggedFrame(sourceGeneration, image)
    }

    internal fun invokeCallbackForTest(block: () -> Unit): Boolean =
        lifecycle.invokeCallback(lifecycle.capture(), block)

    internal fun captureSourceGenerationForTest(): Long = lifecycle.capture()

    private suspend fun trackedAsyncOperation(
        name: String,
        sourceGeneration: Long,
        operation: suspend () -> Unit,
    ) {
        try {
            beforeAsyncOperationForTest?.invoke(name, sourceGeneration)
            operation()
        } finally {
            asyncOperationCompletedForTest?.invoke(name, sourceGeneration)
        }
    }

    internal fun sourceOpeningForTest(): Boolean = playbackContextFor(lifecycle.capture())?.isOpening() == true

    internal suspend fun updatePositionForTest(sourceGeneration: Long) {
        val playback = playbackContextFor(sourceGeneration) ?: return
        updatePositionAsync(playback, playback.completion.captureGeneration(), sourceGeneration)
    }

    internal suspend fun runBufferingPublicationForTest(sourceGeneration: Long) {
        withContext(Dispatchers.Main) {
            lifecycle.publish(sourceGeneration) {
                isPlaying = true
                isLoading = false
                lastFrameUpdateTime = 0L
            }
        }
        checkBufferingState(sourceGeneration)
    }

    internal fun queuedFrameGenerationForTest(): Long = _currentFrameState.value.sourceGeneration

    internal fun frameJobTicketForTest(): LinuxGenerationJobSlot.Ticket? = frameUpdateJobs.ownerTicketForTest()

    internal fun bufferingJobTicketForTest(): LinuxGenerationJobSlot.Ticket? = bufferingCheckJobs.ownerTicketForTest()

    internal fun seekInProgressForTest(): Boolean = seekInProgress

    internal fun targetSeekTimeForTest(): Double? = targetSeekTime

    internal fun awaitCreationQuiescenceWaitEnteredForTest() {
        creationTracker.awaitQuiescenceWaitEnteredForTest()
    }

    internal suspend fun checkLoopingForTest(
        current: Double,
        duration: Double,
        sourceGeneration: Long = lifecycle.capture(),
    ) {
        val playback = playbackContextFor(sourceGeneration) ?: return
        checkLoopingAsync(current, duration, playback, playback.completion.captureGeneration(), sourceGeneration)
    }

    internal fun restartJobsForTest(sourceGeneration: Long) {
        startFrameUpdates(sourceGeneration)
        startBufferingCheck(sourceGeneration)
    }

    internal fun retirePlayerForTest() {
        lifecycle.retirePlayerForTest()
    }

    internal fun desiredPlayingForTest(): Boolean = playbackContext.desiredPlayingForTest()

    internal fun <T> withPlayerForTest(
        sourceGeneration: Long,
        block: (Long) -> T,
    ): T? = withPlayer(sourceGeneration, block)

    private suspend fun initPlayer() {
        linuxLogger.d { "initPlayer() - Creating native player" }
        var failure: Throwable? = null
        try {
            creationTracker.track {
                val ptr = bridge.createPlayer()
                if (ptr != 0L) {
                    var installed = false
                    try {
                        installed = installCreatedPlayer(initGeneration, ptr)
                        if (installed) {
                            linuxLogger.d { "Native player created successfully" }
                            applyVolume(initGeneration)
                            applyPlaybackSpeed(initGeneration)
                        }
                    } finally {
                        if (!installed) destroyRejectedPlayer(ptr)
                    }
                } else {
                    linuxLogger.e { "Failed to create native player" }
                    withContext(Dispatchers.Main) {
                        lifecycle.publish(initGeneration) {
                            error = VideoPlayerError.UnknownError("Failed to create native player")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            failure = e
            if (e is CancellationException) throw e
            linuxLogger.e { "Exception in initPlayer: ${e.message}" }
            withContext(Dispatchers.Main) {
                lifecycle.publish(initGeneration) {
                    error = VideoPlayerError.UnknownError("Failed to initialize player: ${e.message}")
                }
            }
        } finally {
            initializationCompletedForTest?.invoke(failure)
        }
    }

    private fun checkExistsIfLocalFile(uri: String): Boolean {
        val schemeDelimiter = uri.indexOf("://")
        val scheme = if (schemeDelimiter >= 0) uri.substring(0, schemeDelimiter) else ""
        return when (scheme) {
            "", "file" -> {
                val path = if (scheme == "file") uri.removePrefix("file://") else uri
                File(path).exists()
            }

            else -> {
                true
            }
        }
    }

    override fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState,
    ) {
        openUriInternal(uri, initializeplayerState, null)
    }

    private fun openUriInternal(
        uri: String,
        initialState: InitialPlayerState,
        expectedSourceGeneration: Long?,
    ) {
        var rejectedOpenJob: Job? = null
        var displacedOpenJob: Job? = null
        var replacementOpenJob: Job? = null
        lifecycle.withSourceTransition {
            val sourceGeneration =
                expectedSourceGeneration?.let(lifecycle::beginOpenIfCurrent) ?: lifecycle.beginOpen()
            if (sourceGeneration == 0L) {
                if (lifecycle.capture() == 0L) clearExhaustedSourceState()
                return@withSourceTransition
            }
            val sourcePlayback =
                LinuxSourcePlaybackContext(
                    sourceGeneration,
                    initialState,
                    completion = LinuxPlaybackCompletion(initialPlaybackCompletionGenerationForTest ?: 0L),
                    predecessorAwaitRegisteredForTest = {
                        commandAwaitingPredecessorForTest?.invoke(sourceGeneration)
                    },
                )
            playbackContext = sourcePlayback
            lifecycle.publish(sourceGeneration) {
                _currentFrameState.value = LinuxTaggedFrame(sourceGeneration, null)
                (currentFrameState as MutableState).value = null
            }
            lastUri = uri
            val job =
                ioScope.launch(start = CoroutineStart.LAZY) {
                    var failure: Throwable? = null
                    try {
                        openUriForGeneration(uri, initialState, sourceGeneration)
                    } catch (e: CancellationException) {
                        failure = e
                        throw e
                    } catch (t: Throwable) {
                        failure = t
                        playbackContextFor(sourceGeneration)?.finishOpening(reserveAutoplay = false)
                        publishOpenError(
                            sourceGeneration,
                            VideoPlayerError.SourceError("Error: ${t.message}"),
                        )
                    } finally {
                        sourcePlayback.finishOpening(reserveAutoplay = false)
                        sourceOpenCompletedForTest?.invoke(sourceGeneration, failure)
                    }
                }
            job.invokeOnCompletion {
                synchronized(openRequestLock) {
                    openJobs.remove(job)
                    if (openJob === job) openJob = null
                }
            }
            var registered = false
            synchronized(openRequestLock) {
                if (openJobsClosed || rejectOpenRegistrationForTest?.get() == true) return@synchronized
                displacedOpenJob = openJob
                openJobs.add(job)
                replacementOpenJob = job
                openJob = job
                registered = true
            }
            if (!registered) rejectedOpenJob = job
        }
        rejectedOpenJob?.let {
            beforeOpenJobCancelForTest?.invoke()
            it.cancel()
        }
        displacedOpenJob?.let {
            beforeOpenJobCancelForTest?.invoke()
            it.cancel()
        }
        replacementOpenJob?.start()
        if (replacementOpenJob == null) launchFailedOpenCleanup()
    }

    private fun launchFailedOpenCleanup() {
        lateinit var cleanupJob: Job
        cleanupJob =
            ioScope.launch(start = CoroutineStart.LAZY) {
                var failure: Throwable? = null
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        lifecycle.drainRetired(bridge::disposePlayer)
                    }
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    sourceOpenCompletedForTest?.invoke(0L, failure)
                }
            }
        cleanupJob.invokeOnCompletion {
            synchronized(openRequestLock) { openJobs.remove(cleanupJob) }
        }
        val registered =
            synchronized(openRequestLock) {
                if (openJobsClosed) {
                    false
                } else {
                    openJobs.add(cleanupJob)
                    true
                }
            }
        if (registered) cleanupJob.start() else cleanupJob.cancel()
    }

    private suspend fun openUriForGeneration(
        uri: String,
        initializeplayerState: InitialPlayerState,
        generation: Long,
    ) {
        if (!lifecycle.isCurrent(generation)) return
        beforeOpenCleanupForTest?.invoke()
        cleanupCurrentPlayback(generation)
        currentCoroutineContext().ensureActive()
        if (!lifecycle.isCurrent(generation)) return
        if (!checkExistsIfLocalFile(uri)) {
            playbackContextFor(generation)?.finishOpening(reserveAutoplay = false)
            linuxLogger.e { "File does not exist: $uri" }
            publishOpenError(generation, VideoPlayerError.SourceError("File not found: $uri"))
            return
        }

        playbackContextFor(generation)?.resume?.reset()
        withContext(Dispatchers.Main) {
            lifecycle.publish(generation) {
                clearSeekState()
                isLoading = true
                error = null
                playbackSpeed = 1.0f
                hasMedia = false
                isPlaying = false
            }
        }

        try {
            currentCoroutineContext().ensureActive()
            if (!lifecycle.isCurrent(generation)) return
            ensurePlayerInitialized(generation)
            currentCoroutineContext().ensureActive()

            val result = openMediaUri(uri, generation)
            currentCoroutineContext().ensureActive()
            if (!lifecycle.isCurrent(generation)) return

            if (result) {
                updateFrameRateInfo(generation)
                updateMetadata(generation)
                currentCoroutineContext().ensureActive()
                if (!lifecycle.isCurrent(generation)) return

                if (surfaceWidth > 0 && surfaceHeight > 0) applyOutputScaling(generation)

                withContext(Dispatchers.Main) {
                    lifecycle.publish(generation) {
                        hasMedia = true
                        isLoading = false
                        isPlaying = false
                    }
                }
                if (!lifecycle.isCurrent(generation)) return
                val playback = playbackContextFor(generation) ?: return
                val openingRequest = playback.finishOpening()
                val openingCommand = openingRequest.command
                if (openingCommand != null) {
                    try {
                        commandReservedBeforeLaunchForTest?.invoke("open-autoplay", generation)
                        checkNotNull(currentCoroutineContext()[Job]).invokeOnCompletion {
                            playback.settleIfExecutionNeverStarted(openingCommand) {
                                terminalizeLatestPublicationBlocking(generation, playback, it)
                            }
                        }
                    } catch (failure: Throwable) {
                        playback.settleIfExecutionNeverStarted(openingCommand) {
                            terminalizeLatestPublicationBlocking(generation, playback, it)
                        }
                        throw failure
                    }
                }
                beforeOpeningIntentApplyForTest?.invoke(generation, openingRequest.intent.desiredPlaying)
                if (openingRequest.intent.desiredPlaying) {
                    playInBackground(generation, openingRequest.command, openingRequest.intent)
                }
                startFrameUpdates(generation)
                updateFrameAsync(generation)
                startBufferingCheck(generation)
                lifecycle.invokeCallback(generation) {
                    sourceReadyObserver?.invoke(generation, openingRequest.intent.desiredPlaying)
                }
            } else {
                playbackContextFor(generation)?.finishOpening(reserveAutoplay = false)
                linuxLogger.e { "Failed to open URI" }
                publishOpenError(generation, VideoPlayerError.SourceError("Failed to open media source"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            playbackContextFor(generation)?.finishOpening(reserveAutoplay = false)
            linuxLogger.e { "openUri() - Exception: ${e.message}" }
            publishOpenError(generation, VideoPlayerError.SourceError("Error: ${e.message}"))
        }
    }

    private suspend fun publishOpenError(
        generation: Long,
        playerError: VideoPlayerError,
    ) {
        withContext(Dispatchers.Main) {
            lifecycle.publish(generation) {
                isLoading = false
                error = playerError
            }
        }
    }

    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState,
    ) {
        openUri(file.file.path, initializeplayerState)
    }

    private suspend fun cleanupCurrentPlayback(sourceGeneration: Long) {
        frameUpdateJobs.cancel(frameUpdateJobs.capture(sourceGeneration))
        bufferingCheckJobs.cancel(bufferingCheckJobs.capture(sourceGeneration))
        withContext(NonCancellable + Dispatchers.IO) {
            lifecycle.drainRetired(bridge::disposePlayer)
        }
        if (!lifecycle.isCurrent(sourceGeneration)) return
    }

    private suspend fun ensurePlayerInitialized(sourceGeneration: Long) {
        val hasPlayer = withPlayer(sourceGeneration) { true } == true
        if (!hasPlayer) {
            creationTracker.track {
                val ptr = bridge.createPlayer()
                if (ptr != 0L) {
                    var installed = false
                    try {
                        installed = installCreatedPlayer(sourceGeneration, ptr)
                        if (installed) {
                            applyVolume(sourceGeneration)
                            applyPlaybackSpeed(sourceGeneration)
                        }
                    } finally {
                        if (!installed) destroyRejectedPlayer(ptr)
                    }
                } else {
                    throw IllegalStateException("Failed to create native player")
                }
            }
        }
    }

    private suspend fun installCreatedPlayer(
        sourceGeneration: Long,
        handle: Long,
    ): Boolean {
        while (true) {
            when (val result = lifecycle.tryInstall(sourceGeneration, handle)) {
                LinuxPlayerLifecycle.InstallResult.Installed -> {
                    return true
                }

                LinuxPlayerLifecycle.InstallResult.StaleOrClosed -> {
                    return false
                }

                is LinuxPlayerLifecycle.InstallResult.Poisoned -> {
                    throw result.failure
                }

                is LinuxPlayerLifecycle.InstallResult.WaitForDrain -> {
                    installationWaitingForDrainForTest?.invoke(sourceGeneration)
                    withContext(Dispatchers.IO) { result.completion.join() }
                    currentCoroutineContext().ensureActive()
                    if (!lifecycle.isCurrent(sourceGeneration)) return false
                }
            }
        }
    }

    private suspend fun destroyRejectedPlayer(handle: Long) {
        withContext(NonCancellable + Dispatchers.IO) {
            lifecycle.destroyRejected(handle, bridge::disposePlayer)
        }
    }

    private suspend fun openMediaUri(
        uri: String,
        generation: Long,
    ): Boolean {
        if (!checkExistsIfLocalFile(uri)) {
            publishOpenError(generation, VideoPlayerError.SourceError("File not found: $uri"))
            return false
        }

        return try {
            val opened =
                withPlayer(generation) { ptr ->
                    bridge.openUri(ptr, uri)
                    true
                } ?: false
            if (!opened) return false
            pollDimensionsUntilReady(generation)
            currentCoroutineContext().ensureActive()
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Failed to open URI: ${e.message}" }
            publishOpenError(generation, VideoPlayerError.SourceError("Error opening media: ${e.message}"))
            false
        }
    }

    private suspend fun pollDimensionsUntilReady(
        generation: Long,
        maxAttempts: Int = 20,
    ) {
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            if (!lifecycle.isCurrent(generation)) return
            val dimensions =
                withPlayer(generation) { ptr ->
                    bridge.frameWidth(ptr) to bridge.frameHeight(ptr)
                } ?: return
            val (width, height) = dimensions
            if (width > 0 && height > 0) {
                linuxLogger.d { "Dimensions validated (w=$width, h=$height) after $attempt attempts" }
                return
            }
            linuxLogger.d { "Dimensions not ready yet (attempt $attempt/$maxAttempts)" }
            delay(250)
        }
        linuxLogger.e { "Unable to retrieve valid dimensions after $maxAttempts attempts" }
    }

    private suspend fun updateFrameRateInfo(generation: Long) {
        try {
            val frameRate = withPlayer(generation) { bridge.frameRate(it) } ?: return
            if (!lifecycle.publish(generation) { captureFrameRate = frameRate }) return
            linuxLogger.d { "Frame rate: $captureFrameRate" }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error updating frame rate: ${e.message}" }
        }
    }

    private suspend fun updateMetadata(generation: Long) {
        try {
            val snapshot =
                withPlayer(generation) { ptr ->
                    LinuxNativeMetadataSnapshot(
                        width = bridge.frameWidth(ptr),
                        height = bridge.frameHeight(ptr),
                        durationMillis = (bridge.videoDuration(ptr) * 1000).toLong(),
                        frameRate = bridge.frameRate(ptr),
                        title = bridge.videoTitle(ptr),
                        bitrate = bridge.videoBitrate(ptr),
                        mimeType = bridge.videoMimeType(ptr),
                        audioChannels = bridge.audioChannels(ptr),
                        audioSampleRate = bridge.audioSampleRate(ptr),
                    )
                } ?: return
            if (!lifecycle.isCurrent(generation)) return
            val currentSnapshot = snapshot
            val newAspectRatio =
                if (currentSnapshot.width > 0 && currentSnapshot.height > 0) {
                    currentSnapshot.width.toFloat() / currentSnapshot.height.toFloat()
                } else {
                    _aspectRatio.value
                }

            withContext(Dispatchers.Main) {
                lifecycle.publish(generation) {
                    metadata.duration = currentSnapshot.durationMillis
                    metadata.width = currentSnapshot.width
                    metadata.height = currentSnapshot.height
                    metadata.frameRate = currentSnapshot.frameRate
                    metadata.title = currentSnapshot.title
                    metadata.bitrate = currentSnapshot.bitrate
                    metadata.mimeType = currentSnapshot.mimeType
                    metadata.audioChannels =
                        if (currentSnapshot.audioChannels == 0) null else currentSnapshot.audioChannels
                    metadata.audioSampleRate =
                        if (currentSnapshot.audioSampleRate == 0) null else currentSnapshot.audioSampleRate
                    _aspectRatio.value = newAspectRatio
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error updating metadata: ${e.message}" }
        }
    }

    // --- Frame update loop ---

    private fun startFrameUpdates(
        sourceGeneration: Long = lifecycle.capture(),
        reserved: LinuxGenerationJobSlot.Reservation<Job>? = null,
    ) {
        var reservation = reserved
        if (reservation == null &&
            !lifecycle.publish(sourceGeneration) {
                reservation = frameUpdateJobs.reserveDeferredCancellation(sourceGeneration)
            }
        ) {
            return
        }
        val claimed = reservation ?: return
        frameUpdateJobs.cancelDisplaced(claimed)
        val owner = claimed.ticket
        jobTransitionObserverForTest?.invoke("frame", "reserved", owner)
        val playback = playbackContextFor(sourceGeneration) ?: return
        val playbackGeneration = playback.completion.captureGeneration()
        lateinit var job: Job
        job =
            ioScope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (isActive && lifecycle.isCurrent(sourceGeneration)) {
                        ensureActive()
                        updateFrameAsync(sourceGeneration)
                        if (!userDragging) {
                            updatePositionAsync(playback, playbackGeneration, sourceGeneration)
                        }
                        delay(updateInterval)
                    }
                } finally {
                    jobTransitionObserverForTest?.invoke("frame", "finalizing", owner)
                    frameUpdateJobs.clear(owner, job)
                    jobTransitionObserverForTest?.invoke("frame", "finalized", owner)
                }
            }
        if (frameUpdateJobs.install(owner, job)) {
            jobTransitionObserverForTest?.invoke("frame", "installed", owner)
            job.start()
        } else {
            jobTransitionObserverForTest?.invoke("frame", "rejected", owner)
        }
    }

    private fun stopFrameUpdates(sourceGeneration: Long = lifecycle.capture()) {
        frameUpdateJobs.cancel(frameUpdateJobs.capture(sourceGeneration))
    }

    private fun startBufferingCheck(
        sourceGeneration: Long = lifecycle.capture(),
        reserved: LinuxGenerationJobSlot.Reservation<Job>? = null,
    ) {
        var reservation = reserved
        if (reservation == null &&
            !lifecycle.publish(sourceGeneration) {
                reservation = bufferingCheckJobs.reserveDeferredCancellation(sourceGeneration)
            }
        ) {
            return
        }
        val claimed = reservation ?: return
        bufferingCheckJobs.cancelDisplaced(claimed)
        val owner = claimed.ticket
        jobTransitionObserverForTest?.invoke("buffering", "reserved", owner)
        lateinit var job: Job
        job =
            ioScope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (isActive && lifecycle.isCurrent(sourceGeneration)) {
                        ensureActive()
                        checkBufferingState(sourceGeneration)
                        delay(bufferingCheckInterval)
                    }
                } finally {
                    jobTransitionObserverForTest?.invoke("buffering", "finalizing", owner)
                    bufferingCheckJobs.clear(owner, job)
                    jobTransitionObserverForTest?.invoke("buffering", "finalized", owner)
                }
            }
        if (bufferingCheckJobs.install(owner, job)) {
            jobTransitionObserverForTest?.invoke("buffering", "installed", owner)
            job.start()
        } else {
            jobTransitionObserverForTest?.invoke("buffering", "rejected", owner)
        }
    }

    private suspend fun checkBufferingState(sourceGeneration: Long) {
        if (isPlaying && !isLoading) {
            val timeSinceLastFrame = System.currentTimeMillis() - lastFrameUpdateTime
            if (timeSinceLastFrame > bufferingTimeoutThreshold) {
                beforeBufferingPublicationForTest?.invoke()
                withContext(Dispatchers.Main) {
                    lifecycle.publish(sourceGeneration) { isLoading = true }
                }
            }
        }
    }

    private fun stopBufferingCheck(sourceGeneration: Long = lifecycle.capture()) {
        bufferingCheckJobs.cancel(bufferingCheckJobs.capture(sourceGeneration))
    }

    private fun allocateFrameTargets(
        width: Int,
        height: Int,
    ): Boolean {
        val imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        val targets =
            allocateOwnedFrameTargets(
                create = { Bitmap().apply { allocPixels(imageInfo) } },
                close = Bitmap::close,
                peek = Bitmap::peekPixels,
                address = { it.addr },
                size = { checkedFrameBufferSize(width, height, it.rowBytes) },
                wrap = bridge::wrapPointer,
            ) ?: return false

        val oldBitmapA = skiaBitmapA
        val oldBitmapB = skiaBitmapB
        skiaBitmapA = null
        skiaBitmapB = null
        skiaBitmapABuffer = null
        skiaBitmapBBuffer = null
        skiaBitmapWidth = 0
        skiaBitmapHeight = 0
        nextSkiaBitmapA = true

        val installed =
            transferFrameTargetOwnership(
                oldFirst = oldBitmapA,
                oldSecond = oldBitmapB,
                replacement = targets,
                close = Bitmap::close,
            )
        skiaBitmapA = installed.firstTarget
        skiaBitmapB = installed.secondTarget
        skiaBitmapABuffer = installed.firstBuffer
        skiaBitmapBBuffer = installed.secondBuffer
        skiaBitmapWidth = width
        skiaBitmapHeight = height
        nextSkiaBitmapA = true
        return true
    }

    private suspend fun updateFrameAsync(sourceGeneration: Long = lifecycle.capture()) {
        if (!frameRenderingEnabled) return
        if (!lifecycle.isCurrent(sourceGeneration)) return
        withContext(frameDispatcher) {
            try {
                val copied =
                    frameResourceGate.withResources resources@{
                        val frameToPublish =
                            withPlayer(sourceGeneration) player@{ ptr ->
                                val width = bridge.frameWidth(ptr)
                                val height = bridge.frameHeight(ptr)
                                if (width <= 0 || height <= 0) return@player null

                                if (
                                    skiaBitmapA == null ||
                                    skiaBitmapWidth != width ||
                                    skiaBitmapHeight != height
                                ) {
                                    if (!allocateFrameTargets(width, height)) return@player null
                                }

                                val useBitmapA = nextSkiaBitmapA
                                val targetBitmap = if (useBitmapA) skiaBitmapA else skiaBitmapB
                                val destination = if (useBitmapA) skiaBitmapABuffer else skiaBitmapBBuffer
                                if (targetBitmap == null || destination == null) return@player null
                                val pixmap = targetBitmap.peekPixels() ?: return@player null
                                val destinationSize =
                                    checkedFrameBufferSize(width, height, pixmap.rowBytes)
                                        ?: return@player null
                                if (destination.capacity().toLong() < destinationSize) return@player null

                                val status =
                                    bridge.copyLatestFrame(
                                        handle = ptr,
                                        destination = destination,
                                        expectedWidth = width,
                                        expectedHeight = height,
                                        destinationStride = pixmap.rowBytes,
                                        outInfo = frameCopyInfo,
                                    )

                                if (status != LinuxNativeBridge.FRAME_COPY_OK) {
                                    when (status) {
                                        LinuxNativeBridge.FRAME_COPY_SIZE_CHANGED -> {
                                            linuxLogger.d {
                                                "Frame size changed during copy to ${frameCopyInfo[0]}x${frameCopyInfo[1]}"
                                            }
                                        }

                                        LinuxNativeBridge.FRAME_COPY_DEST_TOO_SMALL,
                                        LinuxNativeBridge.FRAME_COPY_INVALID,
                                        -> {
                                            linuxLogger.e { "Native frame copy rejected layout (status=$status)" }
                                        }
                                    }
                                    return@player null
                                }

                                nextSkiaBitmapA = !useBitmapA
                                Image.makeFromBitmap(targetBitmap).use { it.toComposeImageBitmap() }
                            } ?: return@resources false
                        lifecycle.publish(sourceGeneration) {
                            _currentFrameState.value = LinuxTaggedFrame(sourceGeneration, frameToPublish)
                            lastFrameUpdateTime = System.currentTimeMillis()
                        }
                    }

                if (copied && isLoading && !seekInProgress) {
                    withContext(Dispatchers.Main) {
                        lifecycle.publish(sourceGeneration) { isLoading = false }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                linuxLogger.e { "updateFrameAsync() - Exception: ${e.message}" }
            }
        }
    }

    private suspend fun updatePositionAsync(
        playback: LinuxSourcePlaybackContext,
        playbackGeneration: Long,
        sourceGeneration: Long,
    ) {
        if (!lifecycle.isCurrent(sourceGeneration) || !hasMedia || userDragging) return
        try {
            val duration = getDurationSafely(sourceGeneration)
            if (duration <= 0) return

            val current = getPositionSafely(sourceGeneration)

            withContext(Dispatchers.Main) {
                lifecycle.publish(sourceGeneration) {
                    _positionText.value = formatTime(current)
                    _durationText.value = formatTime(duration)
                }
            }

            if (seekInProgress && targetSeekTime != null) {
                if (abs(current - targetSeekTime!!) < 0.3) {
                    withContext(Dispatchers.Main) {
                        lifecycle.publish(sourceGeneration) {
                            seekInProgress = false
                            targetSeekTime = null
                            isLoading = false
                        }
                    }
                }
            } else {
                val newSliderPos = (current / duration * 1000).toFloat().coerceIn(0f, 1000f)
                withContext(Dispatchers.Main) {
                    lifecycle.publish(sourceGeneration) { sliderPos = newSliderPos }
                }
            }

            checkLoopingAsync(current, duration, playback, playbackGeneration, sourceGeneration)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error in updatePositionAsync: ${e.message}" }
            updatePositionFailureForTest?.invoke(e)
        }
    }

    private suspend fun checkLoopingAsync(
        current: Double,
        duration: Double,
        playback: LinuxSourcePlaybackContext,
        playbackGeneration: Long,
        sourceGeneration: Long,
    ) {
        if (!lifecycle.isCurrent(sourceGeneration)) return
        if (loop) {
            if (seekInProgress) return
            val eosPoll =
                playback.reserveCommand(
                    requiresCurrentIntent = false,
                    claimsPublication = false,
                )
            val reachedEnd =
                playback.runCommand(eosPoll) {
                    if (!lifecycle.isCurrent(sourceGeneration) ||
                        !playback.completion.isCurrent(playbackGeneration)
                    ) {
                        return@runCommand false
                    }
                    val consumed = withPlayer(sourceGeneration) { bridge.consumeDidPlayToEnd(it) } ?: false
                    consumed || (duration > 0 && current >= duration - 0.5)
                } ?: false
            if (!reachedEnd) return

            // Empty polls participate in command ordering without taking managed-publication
            // ownership. Promote an observed EOS only when no public command has superseded it.
            val seekCommand = playback.reservePublicationCommandIfUnchanged(eosPoll) ?: return
            seekToAsync(0f, sourceGeneration, playback, seekCommand, replaceFrameWorker = false) {
                withContext(Dispatchers.Main) {
                    lifecycle.invokeCallback(sourceGeneration) {
                        playback.invokeCallbackIfLatest(it) {
                            onRestart?.invoke()
                            true
                        }
                    }
                }
            }
            return
        }

        val eosPoll =
            playback.reserveCommand(
                requiresCurrentIntent = false,
                claimsPublication = false,
            )
        val reachedEnd =
            playback.runCommand(eosPoll) {
                if (!lifecycle.isCurrent(sourceGeneration) ||
                    !playback.completion.isCurrent(playbackGeneration)
                ) {
                    return@runCommand false
                }
                playback.resume.markEndedIfConsumed(playbackGeneration) {
                    withPlayer(sourceGeneration) { bridge.consumeDidPlayToEnd(it) } ?: false
                }
            } ?: false
        if (!reachedEnd) return

        val eosCommand = playback.reservePublicationCommandIfUnchanged(eosPoll) ?: return
        playback.runCommand(
            eosCommand,
            { terminalizeLatestPublication(sourceGeneration, playback, it) },
        ) {
            if (!lifecycle.isCurrent(sourceGeneration) ||
                !playback.completion.isCurrent(playbackGeneration)
            ) {
                return@runCommand
            }

            val paused =
                withPlayer(sourceGeneration) { player ->
                    bridge.pause(player)
                    true
                } ?: false
            if (!paused || !playback.completion.isCurrent(playbackGeneration)) return@runCommand

            withContext(Dispatchers.Main) {
                playback.commitIfLatest(eosCommand) {
                    lifecycle.publish(sourceGeneration) {
                        if (playback.completion.isCurrent(playbackGeneration)) {
                            isPlaying = false
                            isLoading = false
                        }
                    }
                }
            }
            if (!playback.ownsLatestPublication(eosCommand) ||
                !playback.completion.isCurrent(playbackGeneration) ||
                !lifecycle.isCurrent(sourceGeneration)
            ) {
                return@runCommand
            }
            val eosFrameOwner = frameUpdateJobs.capture(sourceGeneration)
            val eosBufferingOwner = bufferingCheckJobs.capture(sourceGeneration)
            try {
                withContext(Dispatchers.Main) {
                    invokeLifecycleCallbackIfLatestPublication(
                        lifecycle,
                        sourceGeneration,
                        playback,
                        eosCommand,
                    ) {
                        if (playback.completion.isCurrent(playbackGeneration)) {
                            onPlaybackEnded?.invoke()
                        }
                    }
                }
            } finally {
                try {
                    afterPlaybackEndedCallbackForTest?.invoke()
                } finally {
                    try {
                        frameUpdateJobs.cancel(eosFrameOwner)
                    } finally {
                        try {
                            bufferingCheckJobs.cancel(eosBufferingOwner)
                        } finally {
                            eosFinalizationCompletedForTest?.invoke()
                        }
                    }
                }
            }
        }
    }

    // --- Playback controls ---

    private data class IntentRequestSnapshot(
        val sourceGeneration: Long,
        val uri: String?,
        val playback: LinuxSourcePlaybackContext?,
        val request: LinuxSourcePlaybackContext.Request?,
    )

    private fun captureIntentRequest(desiredPlaying: Boolean): IntentRequestSnapshot =
        lifecycle.withSourceTransition {
            val sourceGeneration = lifecycle.capture()
            val playback = playbackContextFor(sourceGeneration)
            IntentRequestSnapshot(sourceGeneration, lastUri, playback, playback?.requestPlaying(desiredPlaying))
        }

    override fun play() {
        val snapshot = captureIntentRequest(true)
        val sourceGeneration = snapshot.sourceGeneration
        val uriAtRequest = snapshot.uri
        val playback = snapshot.playback
        val request = snapshot.request
        if (request?.opening == true) return
        val command = request?.command
        val body: suspend () -> Unit = {
            trackedAsyncOperation("play", sourceGeneration) {
                if (!hasMedia) {
                    if (playback != null && command != null) {
                        playback.runCommand(
                            command,
                            { terminalizeLatestPublication(sourceGeneration, playback, it) },
                        ) {
                            uriAtRequest?.let {
                                openUriInternal(it, InitialPlayerState.PLAY, sourceGeneration)
                            }
                        }
                    } else {
                        uriAtRequest?.let { openUriInternal(it, InitialPlayerState.PLAY, sourceGeneration) }
                    }
                    return@trackedAsyncOperation
                }
                playInBackground(sourceGeneration, request?.command, request?.intent, playback)
            }
        }
        if (playback != null && command != null) {
            launchReservedCommand("play", sourceGeneration, playback, command, body)
        } else {
            ioScope.launch { body() }
        }
    }

    private suspend fun playInBackground(
        sourceGeneration: Long = lifecycle.capture(),
        command: LinuxSourcePlaybackContext.CommandTicket? = null,
        intent: LinuxSourcePlaybackContext.Intent? = null,
        playbackAtRequest: LinuxSourcePlaybackContext? = null,
    ) {
        val playback = playbackAtRequest ?: playbackContextFor(sourceGeneration) ?: return
        try {
            val playCommand: suspend () -> Boolean = playCommand@{
                if (!lifecycle.isCurrent(sourceGeneration)) return@playCommand false
                val played =
                    withPlayer(sourceGeneration) { player ->
                        playback.resume.resume(
                            seekToStart = { bridge.seekTo(player, 0.0) },
                            play = { bridge.play(player) },
                        )
                        true
                    } ?: false
                if (!played || (intent != null && !playback.isCurrent(intent))) {
                    false
                } else {
                    var frameReservation: LinuxGenerationJobSlot.Reservation<Job>? = null
                    var bufferingReservation: LinuxGenerationJobSlot.Reservation<Job>? = null
                    val committed =
                        withContext(Dispatchers.Main) {
                            if (intent == null) {
                                lifecycle.publish(sourceGeneration) {
                                    clearSeekState()
                                    isPlaying = true
                                    frameReservation =
                                        frameUpdateJobs.reserveDeferredCancellation(sourceGeneration)
                                    bufferingReservation =
                                        bufferingCheckJobs.reserveDeferredCancellation(sourceGeneration)
                                }
                            } else {
                                playback.commitIfCurrent(intent) {
                                    lifecycle.publish(sourceGeneration) {
                                        clearSeekState()
                                        isPlaying = true
                                        frameReservation =
                                            frameUpdateJobs.reserveDeferredCancellation(sourceGeneration)
                                        bufferingReservation =
                                            bufferingCheckJobs.reserveDeferredCancellation(sourceGeneration)
                                    }
                                }
                            }
                        }
                    if (committed) {
                        beforePlayWorkerInstallForTest?.invoke()
                        startFrameUpdates(sourceGeneration, frameReservation)
                        startBufferingCheck(sourceGeneration, bufferingReservation)
                        true
                    } else {
                        false
                    }
                }
            }
            if (command == null) {
                playCommand()
            } else {
                playback.runCommand(
                    command,
                    { terminalizeLatestPublication(sourceGeneration, playback, it) },
                    playCommand,
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error in playInBackground: ${e.message}" }
            handleError(e, sourceGeneration, playback, intent)
        }
    }

    override fun pause() {
        val snapshot = captureIntentRequest(false)
        val sourceGeneration = snapshot.sourceGeneration
        val playback = snapshot.playback
        val request = snapshot.request
        if (request?.opening == true) return
        val frameOwner = frameUpdateJobs.capture(sourceGeneration)
        val bufferingOwner = bufferingCheckJobs.capture(sourceGeneration)
        val command = request?.command
        val body: suspend () -> Unit = {
            trackedAsyncOperation("pause", sourceGeneration) {
                pauseInBackground(
                    sourceGeneration,
                    frameOwner,
                    bufferingOwner,
                    request?.command,
                    request?.intent,
                    playback,
                )
            }
        }
        if (playback != null && command != null) {
            launchReservedCommand("pause", sourceGeneration, playback, command, body)
        } else {
            ioScope.launch { body() }
        }
    }

    private suspend fun pauseInBackground(
        sourceGeneration: Long,
        frameOwner: LinuxGenerationJobSlot.Ticket?,
        bufferingOwner: LinuxGenerationJobSlot.Ticket?,
        command: LinuxSourcePlaybackContext.CommandTicket? = null,
        intent: LinuxSourcePlaybackContext.Intent? = null,
        playbackAtRequest: LinuxSourcePlaybackContext? = null,
    ): Boolean {
        val playback = playbackAtRequest ?: playbackContextFor(sourceGeneration) ?: return false
        return try {
            val pauseCommand: suspend () -> Boolean = pauseCommand@{
                if (!lifecycle.isCurrent(sourceGeneration)) return@pauseCommand false
                val paused =
                    withPlayer(sourceGeneration) { player ->
                        playback.resume.runCommand { bridge.pause(player) }
                        true
                    } ?: false
                if (!paused || (intent != null && !playback.isCurrent(intent))) {
                    false
                } else {
                    val committed =
                        withContext(Dispatchers.Main) {
                            if (intent == null) {
                                lifecycle.publish(sourceGeneration) {
                                    clearSeekState()
                                    isPlaying = false
                                }
                            } else {
                                playback.commitIfCurrent(intent) {
                                    lifecycle.publish(sourceGeneration) {
                                        clearSeekState()
                                        isPlaying = false
                                    }
                                }
                            }
                        }
                    if (committed) {
                        updateFrameAsync(sourceGeneration)
                        frameUpdateJobs.cancel(frameOwner)
                        bufferingCheckJobs.cancel(bufferingOwner)
                        true
                    } else {
                        false
                    }
                }
            }
            if (command == null) {
                pauseCommand()
            } else {
                playback.runCommand(
                    command,
                    { terminalizeLatestPublication(sourceGeneration, playback, it) },
                    pauseCommand,
                ) ?: false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error in pauseInBackground: ${e.message}" }
            false
        }
    }

    override fun stop() {
        val snapshot = captureIntentRequest(false)
        val sourceGeneration = snapshot.sourceGeneration
        val playback = snapshot.playback ?: return
        val request = snapshot.request ?: return
        if (request.opening) return
        val command = request.command ?: return
        val frameOwner = frameUpdateJobs.capture(sourceGeneration)
        val bufferingOwner = bufferingCheckJobs.capture(sourceGeneration)
        launchReservedCommand("stop", sourceGeneration, playback, command) {
            trackedAsyncOperation("stop", sourceGeneration) {
                playback.runCommand(
                    command,
                    { terminalizeLatestPublication(sourceGeneration, playback, it) },
                ) {
                    val seekExecuted =
                        withPlayer(sourceGeneration) { player ->
                            playback.resume.runCommand { bridge.pause(player) }
                            afterStopPauseForTest?.invoke(sourceGeneration)
                            if (!playback.isCurrent(request.intent) || !hasMedia) {
                                false
                            } else {
                                val duration = bridge.videoDuration(player)
                                if (duration <= 0.0) {
                                    false
                                } else {
                                    bridge.seekTo(player, 0.0)
                                    true
                                }
                            }
                        } ?: return@runCommand

                    beforeStopManagedCommitForTest?.invoke()
                    val committed =
                        withContext(Dispatchers.Main) {
                            playback.commitIfCurrent(request.intent) {
                                if (seekExecuted) playback.resume.reset()
                                lifecycle.publish(sourceGeneration) {
                                    clearSeekState()
                                    isPlaying = false
                                    if (seekExecuted) {
                                        hasMedia = false
                                        _positionText.value = "00:00"
                                        _durationText.value = "00:00"
                                        _aspectRatio.value = 16f / 9f
                                        error = null
                                        _currentFrameState.value = LinuxTaggedFrame(sourceGeneration, null)
                                    }
                                }
                            }
                        }
                    if (committed) {
                        frameUpdateJobs.cancel(frameOwner)
                        bufferingCheckJobs.cancel(bufferingOwner)
                        stopManagedCommitCompletedForTest?.invoke()
                    }
                }
            }
        }
    }

    override fun seekTo(value: Float) {
        val snapshot =
            lifecycle.withSourceTransition {
                val sourceGeneration = lifecycle.capture()
                val playback = playbackContextFor(sourceGeneration) ?: return@withSourceTransition null
                Triple(
                    sourceGeneration,
                    playback,
                    playback.reserveCommand(requiresCurrentIntent = false),
                )
            } ?: return
        val (sourceGeneration, playback, seekCommand) = snapshot
        launchReservedCommand("seek", sourceGeneration, playback, seekCommand) {
            trackedAsyncOperation("seek", sourceGeneration) {
                delay(10) // Coalesce rapid seek events while preserving request-time ticket order.
                seekToAsync(value, sourceGeneration, playback, seekCommand)
            }
        }
    }

    private suspend fun awaitSeekTerminalReset() {
        val testAwaiter = awaitSeekTerminalResetForTest
        if (testAwaiter == null) {
            delay(300)
        } else {
            testAwaiter()
        }
    }

    private suspend fun seekToAsync(
        value: Float,
        sourceGeneration: Long = lifecycle.capture(),
        replaceFrameWorker: Boolean = true,
        afterSuccessfulSeek: suspend (LinuxSourcePlaybackContext.CommandTicket) -> Unit = {},
    ): Boolean {
        val playback = playbackContextFor(sourceGeneration) ?: return false
        return seekToAsync(
            value,
            sourceGeneration,
            playback,
            playback.reserveCommand(requiresCurrentIntent = false),
            replaceFrameWorker,
            afterSuccessfulSeek,
        )
    }

    private suspend fun seekToAsync(
        value: Float,
        sourceGeneration: Long,
        playback: LinuxSourcePlaybackContext,
        seekCommand: LinuxSourcePlaybackContext.CommandTicket,
        replaceFrameWorker: Boolean = true,
        afterSuccessfulSeek: suspend (LinuxSourcePlaybackContext.CommandTicket) -> Unit = {},
    ): Boolean =
        playback.runCommand(
            seekCommand,
            { terminalizeLatestPublication(sourceGeneration, playback, it) },
        ) {
            try {
                if (!lifecycle.isCurrent(sourceGeneration)) return@runCommand false
                val admitted =
                    withContext(Dispatchers.Main) {
                        playback.commitIfLatest(seekCommand) {
                            lifecycle.publish(sourceGeneration) { isLoading = true }
                        }
                    }
                if (!admitted) return@runCommand false

                val duration = getDurationSafely(sourceGeneration)
                if (duration <= 0) {
                    withContext(Dispatchers.Main) {
                        playback.commitIfLatest(seekCommand) {
                            lifecycle.publish(sourceGeneration) { isLoading = false }
                        }
                    }
                    return@runCommand false
                }

                val seekTime = ((value / 1000f) * duration.toFloat()).coerceIn(0f, duration.toFloat())
                val seekPublished =
                    withContext(Dispatchers.Main) {
                        playback.commitIfLatest(seekCommand) {
                            lifecycle.publish(sourceGeneration) {
                                seekInProgress = true
                                targetSeekTime = seekTime.toDouble()
                                sliderPos = value
                                lastFrameUpdateTime = System.currentTimeMillis()
                            }
                        }
                    }
                if (!seekPublished) return@runCommand false

                val wasPlaying = isPlaying
                val sought =
                    withPlayer(sourceGeneration) { ptr ->
                        playback.resume.resetAndRun {
                            bridge.seekTo(ptr, seekTime.toDouble())
                            if (wasPlaying) bridge.play(ptr)
                        }
                        true
                    } ?: false
                if (!sought) {
                    withContext(Dispatchers.Main) {
                        playback.commitIfLatest(seekCommand) {
                            lifecycle.publish(sourceGeneration) { isLoading = false }
                        }
                    }
                    return@runCommand false
                }
                afterSeekNativeCommandForTest?.invoke()

                if (wasPlaying) {
                    if (replaceFrameWorker) {
                        var frameReservation: LinuxGenerationJobSlot.Reservation<Job>? = null
                        val workerCommitted =
                            withContext(Dispatchers.Main) {
                                playback.commitIfLatest(seekCommand) {
                                    lifecycle.publish(sourceGeneration) {
                                        frameReservation =
                                            frameUpdateJobs.reserveDeferredCancellation(sourceGeneration)
                                    }
                                }
                            }
                        if (!workerCommitted) return@runCommand false
                        beforeSeekWorkerInstallForTest?.invoke()
                        startFrameUpdates(sourceGeneration, frameReservation)
                    } else if (!playback.ownsLatestPublication(seekCommand)) {
                        return@runCommand false
                    }
                    delay(10)
                    updateFrameAsync(sourceGeneration)
                    awaitSeekTerminalReset()
                    withContext(Dispatchers.Main) {
                        playback.commitIfLatest(seekCommand) {
                            lifecycle.publish(sourceGeneration) {
                                if (seekInProgress) {
                                    seekInProgress = false
                                    targetSeekTime = null
                                    isLoading = false
                                }
                            }
                        }
                    }
                    afterSeekTerminalResetPublicationForTest?.invoke()
                } else {
                    delay(50)
                    updateFrameAsync(sourceGeneration)
                    if (!lifecycle.isCurrent(sourceGeneration)) return@runCommand false
                    withContext(Dispatchers.Main) {
                        playback.commitIfLatest(seekCommand) {
                            lifecycle.publish(sourceGeneration) {
                                seekInProgress = false
                                targetSeekTime = null
                                isLoading = false
                            }
                        }
                    }
                }
                if (!playback.ownsLatestPublication(seekCommand)) return@runCommand false
                beforeSeekSuccessCallbackDispatchForTest?.invoke()
                afterSuccessfulSeek(seekCommand)
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                linuxLogger.e { "Error in seekToAsync: ${e.message}" }
                withContext(Dispatchers.Main) {
                    playback.commitIfLatest(seekCommand) {
                        lifecycle.publish(sourceGeneration) {
                            isLoading = false
                            seekInProgress = false
                            targetSeekTime = null
                        }
                    }
                }
                false
            }
        } ?: false

    override fun clearError() {
        val sourceGeneration = lifecycle.capture()
        // Main.immediate: when already on the main thread (Compose effects call
        // this from it) run inline instead of dispatching onto the thread that
        // runBlocking just parked - plain Dispatchers.Main deadlocks the EDT.
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                lifecycle.publish(sourceGeneration) { error = null }
            }
        }
    }

    override fun toggleFullscreen() {
        isFullscreen = !isFullscreen
    }

    override fun dispose() {
        beginDisposal().whenComplete { _, failure ->
            if (failure != null) {
                linuxLogger.e { "Linux player disposal failed: ${failure.stackTraceToString()}" }
            }
        }
    }

    override suspend fun disposeAndAwait() {
        kotlin.coroutines.suspendCoroutine<Unit> { continuation ->
            disposeAsync().whenComplete { _, failure ->
                if (failure == null) {
                    continuation.resumeWith(Result.success(Unit))
                } else {
                    continuation.resumeWith(Result.failure(failure))
                }
            }
        }
    }

    private fun awaitMandatoryDisposalBoundary(
        boundary: DisposalWaitBoundary,
        failures: LinuxDisposalFailureCollector,
        wait: () -> Unit,
    ) {
        while (true) {
            failures.attempt { disposalWaitAttemptForTest?.invoke(boundary) }
            try {
                wait()
                return
            } catch (interrupted: InterruptedException) {
                failures.record(interrupted)
                Thread.interrupted()
                failures.attempt { disposalInterruptedForTest?.invoke() }
                failures.attempt { disposalWaitInterruptedForTest?.invoke(boundary, interrupted) }
            }
        }
    }

    private fun awaitJobsTerminal(
        jobs: List<Job>,
        boundary: DisposalWaitBoundary,
        failures: LinuxDisposalFailureCollector,
    ) {
        if (jobs.isEmpty()) return
        awaitMandatoryDisposalBoundary(boundary, failures) {
            val terminal = CountDownLatch(jobs.size)
            val registrations = jobs.map { job -> job.invokeOnCompletion { terminal.countDown() } }
            try {
                try {
                    disposalJobWaitRegisteredForTest?.invoke(boundary)
                } catch (_: Throwable) {
                    // Diagnostic observers must not abort mandatory job drainage.
                }
                terminal.await()
            } finally {
                registrations.forEach { it.dispose() }
            }
        }
    }

    private fun performDisposal(failures: LinuxDisposalFailureCollector) {
        var openJobsToJoin: List<Job> = emptyList()
        var bitmapsToClose: Pair<Bitmap?, Bitmap?> = null to null

        failures.attempt { disposalWorkerStartedForTest?.invoke() }
        failures.attempt { creationTracker.close() }
        while (true) {
            try {
                lifecycle.withSourceTransition {
                    lifecycle.close()
                    synchronized(openRequestLock) {
                        openJobsClosed = true
                        openJobsToJoin = openJobs.toList()
                        openJob = null
                    }
                }
                break
            } catch (interrupted: InterruptedException) {
                failures.record(interrupted)
                Thread.interrupted()
                failures.attempt { disposalInterruptedForTest?.invoke() }
            }
        }
        failures.attempt { disposalOpenJobsSnapshottedForTest?.invoke(openJobsToJoin.size) }
        openJobsToJoin.forEach { job ->
            failures.attempt { beforeOpenJobCancelForTest?.invoke() }
            failures.attempt { job.cancel() }
        }
        failures.attempt { frameUpdateJobs.cancelCurrent() }
        failures.attempt { bufferingCheckJobs.cancelCurrent() }
        failures.attempt { uiUpdateJob?.cancel() }
        failures.attempt { ioRootJob.cancel() }
        failures.attempt { disposalIoRootCancelledForTest?.invoke() }

        failures.attempt {
            bitmapsToClose =
                frameResourceGate.withResources {
                    val detached = skiaBitmapA to skiaBitmapB
                    skiaBitmapABuffer = null
                    skiaBitmapBBuffer = null
                    skiaBitmapA = null
                    skiaBitmapB = null
                    skiaBitmapWidth = 0
                    skiaBitmapHeight = 0
                    nextSkiaBitmapA = true
                    detached
                }
        }
        failures.attempt { managedResourcesDetachedForTest?.invoke() }

        awaitMandatoryDisposalBoundary(DisposalWaitBoundary.CREATION_QUIESCENCE, failures) {
            creationTracker.awaitQuiescence()
        }
        failures.attempt { disposalJoiningOpenJobsForTest?.invoke(openJobsToJoin.size) }
        awaitJobsTerminal(openJobsToJoin, DisposalWaitBoundary.OPEN_JOBS, failures)
        failures.attempt { disposalOpenJobsJoinedForTest?.invoke(openJobsToJoin.size) }
        awaitJobsTerminal(listOf(initializationJob), DisposalWaitBoundary.INITIALIZATION, failures)
        awaitJobsTerminal(listOf(ioRootJob), DisposalWaitBoundary.OPERATION_ROOT, failures)
        failures.attempt { managedResourceCloseForTest?.invoke(0) }
        failures.attempt { bitmapsToClose.first?.close() }
        failures.attempt { managedResourceCloseForTest?.invoke(1) }
        failures.attempt { bitmapsToClose.second?.close() }
        failures.attempt { lifecycle.drainRetired(bridge::disposePlayer) }
    }

    private fun beginDisposal(): CompletableFuture<Unit> {
        disposalFuture.get()?.let { return it }
        val completion = CompletableFuture<Unit>()
        if (!disposalFuture.compareAndSet(null, completion)) return disposalFuture.get()!!

        completion.whenComplete { _, throwable ->
            val failure = unwrapDisposalFailure(throwable)
            try {
                disposalCompletedForTest?.invoke(failure)
            } catch (observerFailure: Throwable) {
                linuxLogger.e { "Disposal completion observer failed: ${observerFailure.stackTraceToString()}" }
            }
        }
        val execution = LinuxDisposalExecution(completion, cleanup = ::performDisposal)
        try {
            execution.submissionSucceeded(disposalWorkerLauncher(execution))
        } catch (launchFailure: Throwable) {
            execution.submissionFailed(launchFailure)
        }
        return completion
    }

    fun disposeAsync(): CompletableFuture<Unit> {
        val completion = beginDisposal()
        if (!lifecycle.isCallbackActiveOnCurrentThread()) return completion
        return CompletableFuture.failedFuture(
            IllegalStateException("Cannot await player disposal from a lifecycle callback"),
        )
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
        val sourceGeneration = lifecycle.capture()
        val requestedWidth = width
        val requestedHeight = height
        val requestedVideoRatio = _aspectRatio.value
        resizeJob =
            ioScope.launch {
                trackedAsyncOperation("resize", sourceGeneration) {
                    delay(120)
                    try {
                        beforeResizeApplyForTest?.invoke(requestedWidth, requestedHeight)
                        applyOutputScaling(sourceGeneration, requestedWidth, requestedHeight, requestedVideoRatio)
                    } finally {
                        isResizing.set(false)
                    }
                }
            }
    }

    private suspend fun applyOutputScaling(
        sourceGeneration: Long = lifecycle.capture(),
        requestedWidth: Int = surfaceWidth,
        requestedHeight: Int = surfaceHeight,
        requestedVideoRatio: Float = _aspectRatio.value,
    ) = resizeApplyMutex.withLock {
        if (!lifecycle.isCurrent(sourceGeneration)) return@withLock
        val sw = requestedWidth
        val sh = requestedHeight
        if (sw <= 0 || sh <= 0) return@withLock

        // Compute output dimensions that fit within the surface while preserving
        // the video's native aspect ratio. Passing the raw surface size would let
        // GStreamer stretch the frame to an arbitrary ratio.
        val videoRatio = requestedVideoRatio
        val surfaceRatio = sw.toFloat() / sh.toFloat()

        val (outW, outH) =
            if (videoRatio > surfaceRatio) {
                // Video is wider than surface → fit to width
                sw to (sw / videoRatio).toInt().coerceAtLeast(1)
            } else {
                // Video is taller than surface → fit to height
                (sh * videoRatio).toInt().coerceAtLeast(1) to sh
            }

        withPlayer(sourceGeneration) { bridge.setOutputSize(it, outW, outH) }
    }

    // --- Internal helpers ---

    private suspend fun resetState(sourceGeneration: Long) {
        playbackContextFor(sourceGeneration)?.resume?.reset()
        withContext(Dispatchers.Main) {
            lifecycle.publish(sourceGeneration) {
                hasMedia = false
                isPlaying = false
                isLoading = false
                _positionText.value = "00:00"
                _durationText.value = "00:00"
                _aspectRatio.value = 16f / 9f
                error = null
            }
        }
        lifecycle.publish(sourceGeneration) {
            _currentFrameState.value = LinuxTaggedFrame(sourceGeneration, null)
        }
    }

    private suspend fun handleError(
        e: Exception,
        sourceGeneration: Long = lifecycle.capture(),
        playback: LinuxSourcePlaybackContext? = null,
        intent: LinuxSourcePlaybackContext.Intent? = null,
    ) {
        withContext(Dispatchers.Main) {
            val publishError = {
                lifecycle.publish(sourceGeneration) {
                    isLoading = false
                    error = VideoPlayerError.SourceError("Error: ${e.message}")
                }
            }
            if (playback != null && intent != null) playback.commitIfCurrent(intent, publishError) else publishError()
        }
    }

    private suspend fun getPositionSafely(sourceGeneration: Long = lifecycle.capture()): Double =
        try {
            withPlayer(sourceGeneration) { bridge.currentTime(it) } ?: 0.0
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            0.0
        }

    private suspend fun getDurationSafely(sourceGeneration: Long = lifecycle.capture()): Double =
        try {
            withPlayer(sourceGeneration) { bridge.videoDuration(it) } ?: 0.0
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            0.0
        }

    private suspend fun applyVolume(
        sourceGeneration: Long = lifecycle.capture(),
        requestedVolume: Float? = null,
        request: Long? = null,
    ) {
        volumeApplyMutex.withLock {
            val (effectiveRequest, effectiveVolume) =
                synchronized(volumeRequestLock) {
                    if (request == null) {
                        reserveVolumeRequestLocked() to _volumeState.value
                    } else {
                        request to checkNotNull(requestedVolume)
                    }
                }
            if (synchronized(volumeRequestLock) { effectiveRequest != latestVolumeRequest }) return
            try {
                withPlayer(sourceGeneration) { bridge.setVolume(it, effectiveVolume) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private suspend fun applyPlaybackSpeed(
        sourceGeneration: Long = lifecycle.capture(),
        requestedSpeed: Float? = null,
        request: Long? = null,
    ) {
        speedApplyMutex.withLock {
            val (effectiveRequest, effectiveSpeed) =
                synchronized(speedRequestLock) {
                    if (request == null) {
                        reserveSpeedRequestLocked() to _playbackSpeedState.value
                    } else {
                        request to checkNotNull(requestedSpeed)
                    }
                }
            if (synchronized(speedRequestLock) { effectiveRequest != latestSpeedRequest }) return
            try {
                withPlayer(sourceGeneration) { bridge.setPlaybackSpeed(it, effectiveSpeed) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private fun <T> withPlayer(
        sourceGeneration: Long = lifecycle.capture(),
        block: (Long) -> T,
    ): T? = lifecycle.withPlayer(sourceGeneration, block)
}
