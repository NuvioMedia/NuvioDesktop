package com.nuvio.app.features.player.desktop

import androidx.compose.ui.graphics.Color
import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.PlayerControlAddonSubtitleItem
import com.nuvio.app.features.player.PlayerControlEpisodeItem
import com.nuvio.app.features.player.PlayerControlFilterItem
import com.nuvio.app.features.player.PlayerControlSeasonItem
import com.nuvio.app.features.player.PlayerControlSourceItem
import com.nuvio.app.features.player.PlayerControlSubtitleCueItem
import com.nuvio.app.features.player.PlayerControlSubtitleLanguageItem
import com.nuvio.app.features.player.PlayerControlSubtitleOptionItem
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.ParentalWarning
import com.nuvio.app.features.player.PlayerControlsAction
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.app.features.player.SUBTITLE_DELAY_MIN_MS
import com.nuvio.app.features.player.SubtitleColorSwatches
import com.nuvio.app.features.player.SubtitleOutlineColorSwatches
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.inferForcedSubtitleTrack
import com.nuvio.app.features.player.toStorageHexString
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CountDownLatch
import javax.swing.SwingUtilities
import kotlin.concurrent.Volatile

internal typealias NativePlayerCreate = (
    Long,
    String,
    Array<String>,
    Boolean,
    Long,
    String,
    Int,
    Boolean,
    NativePlayerEventSink,
) -> Long

internal class NativePlayerController(
    private val host: NativePlayerHost,
    private val nativeCreate: NativePlayerCreate = NativePlayerBridge::create,
    private val nativeDispose: (Long) -> Unit = NativePlayerBridge::dispose,
    private val nativeSeekTo: (Long, Long) -> Unit = NativePlayerBridge::seekTo,
    private val isHostDisplayable: () -> Boolean = { host.isDisplayable },
    private val resolveHostView: () -> Long = { AwtNativeViewResolver.resolveNativeViewPointer(host) },
    private val createWaitTimeoutMs: Long = 5_000L,
    private val releaseTimeoutMs: Long = 10_000L,
    private val onCreateWaitCompleted: () -> Unit = {},
) : PlayerEngineController {
    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val log = Logger.withTag("NativePlayerControls")

        /** Cap on waiting for the previous player's teardown so a hung one cannot block playback. */
        const val TEARDOWN_WAIT_MS = 5_000L

        @Volatile
        var rememberedVolumeLevel: Float = DesktopPlayerVolumeStorage.loadVolumeLevel() ?: 1f

        @Volatile
        var rememberedResizeMode: PlayerResizeMode = PlayerResizeMode.Fit
    }

    private data class ReleaseCallback(
        val onReleased: () -> Unit,
        val onFailed: (String) -> Unit,
    )

    private val lifecycleLock = Any()

    @Volatile
    private var handle: Long = 0L

    /** Native teardown of the previous player, if one is still running. */
    @Volatile
    private var disposeInFlight: Thread? = null

    @Volatile
    private var pendingSource: PendingSource? = null
    @Volatile
    private var releaseRequested: Boolean = false
    private val createsInFlight = mutableSetOf<Thread>()
    private var createWaitInFlight: Thread? = null
    private val releaseCallbacks = mutableListOf<ReleaseCallback>()
    private var releaseInFlight: Thread? = null
    private var releaseTimedOut: Boolean = false
    private var terminalReleaseFailure: String? = null
    private var controlsState = PlayerControlsState()
    @Volatile
    private var currentVolumeLevel = rememberedVolumeLevel.coerceDesktopPlayerVolumeLevel()
    private var pendingSubtitleDelayMs: Int? = null
    private var pendingSubtitleStyle: SubtitleStyleState? = null
    private var pendingUseLibass: Boolean = false
    private var lastSentControlsStructureKey: NativeControlsStructureKey? = null
    private var onAction: (PlayerControlsAction) -> Boolean = { false }
    private var onEvent: (String, Double) -> Boolean = { _, _ -> false }
    private var onScrubChange: (Long) -> Boolean = { false }
    private var onScrubFinished: (Long) -> Boolean = { false }
    private val eventSink = NativePlayerEventSink { type, value ->
        SwingUtilities.invokeLater {
            handlePlayerEvent(type, value)
        }
    }

    fun attach(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        playWhenReady: Boolean,
        initialPositionMs: Long,
        decoderPriority: Int,
        nvidiaRtxSuperResolutionEnabled: Boolean,
        onError: (String?) -> Unit,
    ) {
        val pending = PendingSource(
            sourceUrl = sourceUrl,
            headerLines = sourceHeaders.toHeaderLines(),
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs.coerceAtLeast(0L),
            decoderPriority = decoderPriority,
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled,
            onError = onError,
        )
        var terminalFailure: String? = null
        val accepted = synchronized(lifecycleLock) {
            when {
                releaseRequested -> false
                terminalReleaseFailure != null -> {
                    terminalFailure = terminalReleaseFailure
                    false
                }
                else -> {
                    pendingSource = pending
                    true
                }
            }
        }
        if (!accepted) {
            terminalFailure?.let { message -> SwingUtilities.invokeLater { pending.onError(message) } }
            return
        }
        log.d {
            "attach requested source=${sourceUrl.toPlaybackLogKey()} headers=${sourceHeaders.size} " +
                "playWhenReady=$playWhenReady initialPositionMs=$initialPositionMs decoderPriority=$decoderPriority"
        }
        host.onPeerReady = { attachPending() }
        if (isHostDisplayable()) {
            attachPending()
        }
    }

    private fun attachPending() {
        val pending = pendingSource ?: return
        SwingUtilities.invokeLater {
            val terminalFailure = synchronized(lifecycleLock) { terminalReleaseFailure }
            if (terminalFailure != null) {
                if (!releaseRequested && pendingSource === pending) pending.onError(terminalFailure)
                return@invokeLater
            }
            if (
                releaseRequested ||
                pendingSource !== pending ||
                !isHostDisplayable()
            ) {
                return@invokeLater
            }
            if (deferAttachUntilCreatesComplete()) return@invokeLater
            disposePlayerHandle()
            val teardown = disposeInFlight
            if (teardown == null || !teardown.isAlive) {
                createPlayer(pending)
                return@invokeLater
            }
            // The previous player is still tearing down natively. It owns child windows of this
            // same host, so creating the next one on top of it races its teardown and can leave the
            // new player wedged (controls never resized, playback never starts). Wait for it, but
            // off the EDT, because the teardown itself needs the EDT to keep pumping messages.
            Thread({
                runCatching { teardown.join(TEARDOWN_WAIT_MS) }
                val teardownCompleted = !teardown.isAlive
                SwingUtilities.invokeLater {
                    val terminalFailure = synchronized(lifecycleLock) { terminalReleaseFailure }
                    if (terminalFailure != null) {
                        if (!releaseRequested && pendingSource === pending) pending.onError(terminalFailure)
                        return@invokeLater
                    }
                    if (!releaseRequested && isHostDisplayable() && pendingSource === pending) {
                        if (teardownCompleted) {
                            createPlayer(pending)
                        } else {
                            log.w { "attach aborted because previous native teardown exceeded ${TEARDOWN_WAIT_MS}ms" }
                            if (!releaseRequested && pendingSource === pending) {
                                pending.onError("Previous player is still shutting down. Please try again.")
                            }
                        }
                    }
                }
            }, "nuvio-player-attach").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun deferAttachUntilCreatesComplete(): Boolean = synchronized(lifecycleLock) {
        createsInFlight.removeAll { worker -> !worker.isAlive }
        if (createsInFlight.isEmpty()) return@synchronized false
        if (createWaitInFlight?.isAlive == true) return@synchronized true
        val workers = createsInFlight.toList()
        val pendingAtWaitStart = pendingSource
        val worker = Thread({
            val deadlineNanos = System.nanoTime() + createWaitTimeoutMs * 1_000_000L
            var interrupted = false
            workers.forEach { create ->
                while (create.isAlive) {
                    val remainingNanos = deadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0L) break
                    try {
                        val millis = (remainingNanos / 1_000_000L).coerceAtLeast(1L)
                        create.join(millis)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            synchronized(lifecycleLock) {
                if (createWaitInFlight === Thread.currentThread()) createWaitInFlight = null
            }
            runCatching { onCreateWaitCompleted() }
            SwingUtilities.invokeLater {
                if (releaseRequested || !runCatching { isHostDisplayable() }.getOrDefault(false)) {
                    return@invokeLater
                }
                val (current, terminalFailure) = synchronized(lifecycleLock) {
                    createsInFlight.removeAll { create -> !create.isAlive }
                    pendingSource to terminalReleaseFailure
                }
                if (terminalFailure != null) {
                    current?.onError(terminalFailure)
                    return@invokeLater
                }
                val capturedCreatesRemain = workers.any { create -> create.isAlive }
                when {
                    current == null -> Unit
                    !capturedCreatesRemain -> attachPending()
                    current !== pendingAtWaitStart -> attachPending()
                    else -> current.onError("Previous player is still starting. Please try again.")
                }
            }
        }, "nuvio-player-create-wait").apply { isDaemon = true }
        createWaitInFlight = worker
        worker.start()
        true
    }

    private fun createPlayer(pending: PendingSource) {
        val terminalFailure = synchronized(lifecycleLock) { terminalReleaseFailure }
        if (terminalFailure != null) {
            if (!releaseRequested && pendingSource === pending) pending.onError(terminalFailure)
            return
        }
        // Resolving the AWT peer must happen on the EDT; everything after it must not.
        val hostViewPtr = runCatching { resolveHostView() }
            .getOrElse { error ->
                log.w(error) { "attach failed to resolve host source=${pending.sourceUrl.toPlaybackLogKey()}" }
                if (!releaseRequested && pendingSource === pending) pending.onError(error.message)
                return
            }
        val resolvedSource = if (pending.sourceUrl.startsWith("file:", ignoreCase = true)) {
            runCatching { java.io.File(java.net.URI(pending.sourceUrl)).absolutePath }.getOrElse {
                val stripped = pending.sourceUrl.replaceFirst(Regex("^file:/{1,3}", RegexOption.IGNORE_CASE), "")
                runCatching { java.net.URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped)
            }
        } else {
            pending.sourceUrl
        }

        // Native create blocks until the player's own UI thread finishes initialising, and that
        // thread creates child windows of the AWT host, which needs the EDT to keep pumping
        // messages. Creating on the EDT is therefore the same circular wait that the teardown had:
        // the app stops responding and Windows closes it as "stopped interacting" (Hang 1002).
        // Create off the EDT and come back to it for the parts that touch Swing state.
        val createWorker = Thread({
            try {
                runCatching {
                    nativeCreate(
                        hostViewPtr,
                        resolvedSource,
                        pending.headerLines.toTypedArray(),
                        pending.playWhenReady,
                        pending.initialPositionMs,
                        NativePlayerBridge.controlsPageUrl,
                        pending.decoderPriority,
                        pending.nvidiaRtxSuperResolutionEnabled,
                        eventSink,
                    ).also { if (it == 0L) error("Native player did not return a handle.") }
                }.onSuccess { created ->
                    val accepted = synchronized(lifecycleLock) {
                        if (!releaseRequested && terminalReleaseFailure == null && pendingSource === pending) {
                            handle = created
                            true
                        } else {
                            false
                        }
                    }
                    if (!accepted) {
                        runCatching { nativeDispose(created) }
                            .onFailure { error -> recordTerminalDisposeFailure(error, "superseded create", created) }
                        return@onSuccess
                    }
                    SwingUtilities.invokeLater {
                        val shouldConfigure = synchronized(lifecycleLock) {
                            when {
                                handle != created -> false
                                pendingSource !== pending || !isHostDisplayable() -> {
                                    handle = 0L
                                    lastSentControlsStructureKey = null
                                    startTrackedDisposeLocked(created)
                                    false
                                }
                                else -> true
                            }
                        }
                        if (!shouldConfigure) return@invokeLater
                        log.d {
                            "attach created handle=$created source=${resolvedSource.toPlaybackLogKey()} " +
                                "initialPositionMs=${pending.initialPositionMs}"
                        }
                        applyRememberedVolume()
                        updateControls(controlsState)
                        setResizeMode(rememberedResizeMode)
                        applyPendingSubtitleSettings()
                    }
                }.onFailure { error ->
                    log.w(error) { "attach failed source=${pending.sourceUrl.toPlaybackLogKey()}" }
                    SwingUtilities.invokeLater {
                        if (!releaseRequested && pendingSource === pending) pending.onError(error.message)
                    }
                }
            } finally {
                synchronized(lifecycleLock) {
                    createsInFlight.remove(Thread.currentThread())
                }
            }
        }, "nuvio-player-create").apply { isDaemon = true }
        var admissionFailure: String? = null
        val admitted = synchronized(lifecycleLock) {
            when {
                releaseRequested || pendingSource !== pending -> false
                terminalReleaseFailure != null -> {
                    admissionFailure = terminalReleaseFailure
                    false
                }
                else -> {
                    createsInFlight += createWorker
                    createWorker.start()
                    true
                }
            }
        }
        if (!admitted) {
            admissionFailure?.let { message ->
                SwingUtilities.invokeLater {
                    if (!releaseRequested && pendingSource === pending) pending.onError(message)
                }
            }
            return
        }
    }

    fun setControlCallbacks(
        onAction: (PlayerControlsAction) -> Boolean,
        onEvent: (String, Double) -> Boolean,
        onScrubChange: (Long) -> Boolean,
        onScrubFinished: (Long) -> Boolean,
    ) {
        this.onAction = onAction
        this.onEvent = onEvent
        this.onScrubChange = onScrubChange
        this.onScrubFinished = onScrubFinished
        log.d { "control callbacks attached handle=$handle" }
        host.onCursorActivity = {
            this.onEvent("cursorActivity", 0.0)
        }
    }

    // The embedded controls WebView can lose keyboard focus when the desktop
    // window is reactivated (for example after alt-tab). Re-apply native focus
    // whenever the host window gains focus. Returns the uninstaller; null until
    // the host is inside a window (the ancestor does not exist before first paint).
    fun installWindowFocusForwarding(): (() -> Unit)? {
        val window = SwingUtilities.getWindowAncestor(host) ?: return null
        val listener = object : WindowAdapter() {
            override fun windowGainedFocus(event: WindowEvent) {
                requestKeyboardFocus()
            }
        }
        window.addWindowFocusListener(listener)
        return { window.removeWindowFocusListener(listener) }
    }

    @Synchronized
    fun updateControls(state: PlayerControlsState) {
        host.setControlsVisible(state.controlsVisible)
        val currentHandle = handle
        val current = currentHandle.takeIf { it != 0L } ?: run {
            controlsState = state
            return
        }
        val stateWithVolume = state.copy(
            volumeLevel = resolveDesktopPlayerVolumeLevel(
                requestedLevel = state.volumeLevel,
                currentLevel = currentVolumeLevel,
                rememberedLevel = rememberedVolumeLevel,
            ),
        )
        currentVolumeLevel = stateWithVolume.volumeLevel ?: currentVolumeLevel
        controlsState = stateWithVolume
        val isFullscreen = isDesktopAppFullscreen(SwingUtilities.getWindowAncestor(host))
        val structureKey = NativeControlsStructureKey(
            state = stateWithVolume.nativeControlsStructureKey(),
            isFullscreen = isFullscreen,
        )
        if (structureKey == lastSentControlsStructureKey) return
        lastSentControlsStructureKey = structureKey
        log.d {
            "updateControls handle=$current title=${stateWithVolume.title.take(40)} " +
                "pos=${stateWithVolume.positionMs} duration=${stateWithVolume.durationMs} " +
                "speed=${stateWithVolume.playbackSpeedLabel} audioLabel=${stateWithVolume.audioLabel} " +
                "subsLabel=${stateWithVolume.subtitlesLabel} fullscreen=$isFullscreen"
        }
        NativePlayerBridge.updateControls(current, stateWithVolume.toControlsJson(isFullscreen))
    }

    fun onDesktopFullscreenChanged() {
        lastSentControlsStructureKey = null
        updateControls(controlsState)
        requestKeyboardFocus()
    }

    private fun requestKeyboardFocus() {
        SwingUtilities.invokeLater {
            if (!isHostDisplayable()) return@invokeLater
            host.requestFocusInWindow()
            val current = handle.takeIf { it != 0L } ?: return@invokeLater
            NativePlayerBridge.requestFocus(current)
        }
    }

    fun setResizeMode(mode: PlayerResizeMode) {
        rememberedResizeMode = mode
        handle.takeIf { it != 0L }?.let { current ->
            NativePlayerBridge.setResizeMode(
                handle = current,
                mode = when (mode) {
                    PlayerResizeMode.Fit -> 0
                    PlayerResizeMode.Fill -> 1
                    PlayerResizeMode.Zoom -> 2
                    PlayerResizeMode.Stretch -> 3
                },
            )
        }
    }

    private fun handlePlayerEvent(type: String, value: Double) {
        if (type.shouldLogNativeControlEvent()) {
            log.d { "event received handle=$handle type=$type value=$value" }
        }
        when (type) {
            "cursorActivity" -> host.noteCursorActivity()
            "scrubChange" -> {
                val handled = onScrubChange(value.toLong())
                log.d { "scrubChange positionMs=${value.toLong()} handled=$handled handle=$handle" }
                if (!handled) {
                    updateLocalProgress(value.toLong())
                }
            }
            "scrubFinish" -> {
                val scrubHandled = onScrubFinished(value.toLong())
                log.d { "scrubFinish positionMs=${value.toLong()} handled=$scrubHandled handle=$handle" }
                if (!scrubHandled) {
                    seekTo(value.toLong())
                }
            }
            "toggleFullscreen" -> {
                toggleDesktopAppFullscreen(SwingUtilities.getWindowAncestor(host))
                onDesktopFullscreenChanged()
            }
            "volumeChange" -> setFallbackVolume(value.toFloat())
            "volumeChangeTemporary" -> setTemporaryVolume(value.toFloat())
            "setPlaybackSpeed" -> {
                val speed = value.toFloat()
                setPlaybackSpeed(speed)
            }
            else -> {
                val eventHandled = onEvent(type, value)
                if (type.shouldLogNativeControlEvent()) {
                    log.d { "event delegated type=$type handled=$eventHandled handle=$handle" }
                }
                if (eventHandled) return
                val action = type.toPlayerControlsAction()
                if (action == null) return
                val actionHandled = onAction(action)
                log.d { "action delegated action=$action handled=$actionHandled handle=$handle" }
                if (!actionHandled) {
                    handleFallbackAction(action)
                }
            }
        }
    }

    @Synchronized
    private fun updateLocalProgress(positionMs: Long) {
        controlsState = controlsState.copy(positionMs = positionMs)
        updateControls(controlsState)
    }

    private fun handleFallbackAction(action: PlayerControlsAction) {
        log.d { "fallback action=$action handle=$handle" }
        when (action) {
            PlayerControlsAction.TogglePlayback,
            PlayerControlsAction.KeyboardTogglePlayback -> {
                val current = handle
                if (current == 0L) return
                val isEnded = NativePlayerBridge.isEnded(current)
                val isPaused = NativePlayerBridge.isPaused(current)
                if (isEnded) {
                    NativePlayerBridge.seekTo(current, 0L)
                    NativePlayerBridge.setPaused(current, false)
                } else {
                    NativePlayerBridge.setPaused(current, !isPaused)
                }
            }
            PlayerControlsAction.SeekBack,
            PlayerControlsAction.KeyboardSeekBack -> fallbackSeekBy(-10_000L)
            PlayerControlsAction.SeekForward,
            PlayerControlsAction.KeyboardSeekForward -> fallbackSeekBy(10_000L)
            PlayerControlsAction.KeyboardVolumeDown -> adjustFallbackVolume(-10f)
            PlayerControlsAction.KeyboardVolumeUp -> adjustFallbackVolume(10f)
            PlayerControlsAction.Speed -> cycleFallbackSpeed()
            else -> Unit
        }
    }

    @Synchronized
    private fun adjustFallbackVolume(delta: Float) {
        val current = handle
        if (current != 0L) {
            val currentLevel = resolveDesktopPlayerVolumeLevel(
                requestedLevel = null,
                currentLevel = currentVolumeLevel,
                rememberedLevel = rememberedVolumeLevel,
            )
            val nextLevel = (currentLevel + (delta / 100f)).coerceDesktopPlayerVolumeLevel()
            setFallbackVolume(nextLevel)
        }
    }

    @Synchronized
    private fun setFallbackVolume(level: Float) {
        val current = handle
        if (current != 0L) {
            val nextLevel = level.coerceDesktopPlayerVolumeLevel()
            rememberedVolumeLevel = nextLevel
            currentVolumeLevel = nextLevel
            DesktopPlayerVolumeStorage.saveVolumeLevel(nextLevel)
            NativePlayerBridge.setVolume(current, nextLevel)
            controlsState = controlsState.copy(volumeLevel = nextLevel)
            updateControls(controlsState)
        }
    }

    @Synchronized
    private fun setTemporaryVolume(level: Float) {
        val current = handle
        if (current != 0L) {
            val nextLevel = level.coerceDesktopPlayerVolumeLevel()
            currentVolumeLevel = nextLevel
            NativePlayerBridge.setVolume(current, nextLevel)
            controlsState = controlsState.copy(volumeLevel = nextLevel)
            updateControls(controlsState)
        }
    }

    @Synchronized
    private fun applyRememberedVolume() {
        val current = handle
        if (current == 0L) return
        val level = rememberedVolumeLevel.coerceDesktopPlayerVolumeLevel()
        currentVolumeLevel = level
        NativePlayerBridge.setVolume(current, level)
        controlsState = controlsState.copy(volumeLevel = level)
        log.d { "applied remembered volume level=$level handle=$current" }
    }

    private fun fallbackSeekBy(offsetMs: Long) {
        val current = handle
        if (current != 0L) {
            NativePlayerBridge.seekBy(current, offsetMs)
        }
    }

    private fun cycleFallbackSpeed() {
        val current = handle
        if (current == 0L) return
        val speeds = listOf(1f, 1.25f, 1.5f, 2f)
        val currentSpeed = NativePlayerBridge.speed(current)
        val next = speeds.firstOrNull { it > currentSpeed + 0.01f } ?: speeds.first()
        NativePlayerBridge.setSpeed(current, next)
    }

    fun snapshot(): PlayerPlaybackSnapshot {
        val current = handle
        if (current == 0L) return PlayerPlaybackSnapshot(isLoading = true)
        return runCatching {
            val isLoading = NativePlayerBridge.isLoading(current)
            val isEnded = NativePlayerBridge.isEnded(current)
            PlayerPlaybackSnapshot(
                isLoading = isLoading,
                isPlaying = !NativePlayerBridge.isPaused(current) && !isLoading && !isEnded,
                isEnded = isEnded,
                durationMs = NativePlayerBridge.durationMs(current),
                positionMs = NativePlayerBridge.positionMs(current),
                bufferedPositionMs = NativePlayerBridge.bufferedPositionMs(current),
                playbackSpeed = NativePlayerBridge.speed(current),
            )
        }.getOrDefault(PlayerPlaybackSnapshot(isLoading = true))
    }

    fun releaseBeforeNavigation(onReleased: () -> Unit) {
        releaseBeforeNavigation(onReleased, onReleaseFailed = {})
    }

    override fun releaseBeforeNavigation(
        onReleased: () -> Unit,
        onReleaseFailed: (String) -> Unit,
    ) {
        synchronized(lifecycleLock) {
            releaseRequested = true
        }
        host.resetCursorVisibility()
        invalidateForRelease()
        val callback = ReleaseCallback(onReleased, onReleaseFailed)
        var shouldStart = false
        var immediateFailure: String? = null
        val worker = synchronized(lifecycleLock) {
            val active = releaseInFlight?.takeIf { it.isAlive }
            when {
                terminalReleaseFailure != null -> {
                    immediateFailure = terminalReleaseFailure
                    active ?: Thread.currentThread()
                }
                active != null && releaseTimedOut -> {
                    immediateFailure = "Native player is still shutting down. Please retry or quit Nuvio."
                    active
                }
                else -> {
                    releaseCallbacks += callback
                    active ?: Thread({
                    awaitThreadCompletion(disposeInFlight, "native teardown")
                    awaitInFlightCreates()
                    // A create worker publishes/configures its result on the EDT before leaving
                    // createsInFlight. Release must drain those already-queued callbacks before
                    // taking the final disposal snapshot: a stale publication can reject its
                    // handle and start a tracked disposal after the create thread has exited.
                    awaitSwingCallbacksQueuedByCreates()
                    awaitThreadCompletion(disposeInFlight, "native teardown queued by create")
                    var failureMessage = synchronized(lifecycleLock) { terminalReleaseFailure }
                    val current = if (failureMessage == null) detachPlayerHandle() else 0L
                    if (current != 0L) {
                        log.d { "pre-navigation dispose started handle=$current" }
                        runCatching { nativeDispose(current) }
                            .onSuccess { log.d { "pre-navigation dispose completed handle=$current" } }
                            .onFailure { error ->
                                failureMessage = "Native player shutdown failed. Quit Nuvio before removing the player."
                                log.w(error) { "pre-navigation dispose failed handle=$current" }
                            }
                    }
                    val callbacks = synchronized(lifecycleLock) {
                        releaseInFlight = null
                        releaseTimedOut = false
                        if (failureMessage != null) terminalReleaseFailure = failureMessage
                        releaseCallbacks.toList().also { releaseCallbacks.clear() }
                    }
                    SwingUtilities.invokeLater {
                        callbacks.forEach { pending ->
                            val invoke = if (failureMessage == null) {
                                { pending.onReleased() }
                            } else {
                                { pending.onFailed(failureMessage) }
                            }
                            runCatching(invoke)
                                .onFailure { error -> log.w(error) { "release callback failed" } }
                        }
                    }
                }, "nuvio-player-release").apply {
                    isDaemon = true
                    releaseInFlight = this
                    releaseTimedOut = false
                    shouldStart = true
                }
            }
        }
        }
        if (immediateFailure != null) {
            SwingUtilities.invokeLater {
                runCatching { callback.onFailed(immediateFailure) }
                    .onFailure { error -> log.w(error) { "immediate release failure callback failed" } }
            }
            return
        }
        if (shouldStart) {
            worker.start()
            startReleaseWatchdog(worker)
        }
    }

    private fun startReleaseWatchdog(worker: Thread) {
        Thread({
            try {
                Thread.sleep(releaseTimeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            val callbacks = synchronized(lifecycleLock) {
                if (releaseInFlight !== worker || !worker.isAlive) return@synchronized emptyList()
                releaseTimedOut = true
                releaseCallbacks.toList().also { releaseCallbacks.clear() }
            }
            if (callbacks.isEmpty()) return@Thread
            SwingUtilities.invokeLater {
                callbacks.forEach { pending ->
                    runCatching {
                        pending.onFailed("Native player shutdown timed out. Please retry or quit Nuvio.")
                    }.onFailure { error -> log.w(error) { "release timeout callback failed" } }
                }
            }
        }, "nuvio-player-release-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun dispose() {
        host.resetCursorVisibility()
        val accepted = synchronized(lifecycleLock) {
            if (releaseRequested) {
                false
            } else {
                pendingSource = null
                true
            }
        }
        if (!accepted) return
        host.onPeerReady = null
        disposePlayerHandle()
    }

    private fun cancelPendingAttachment() {
        synchronized(lifecycleLock) {
            pendingSource = null
        }
        host.onPeerReady = null
    }

    private fun invalidateForRelease() {
        cancelPendingAttachment()
        host.onCursorActivity = null
        onAction = { false }
        onEvent = { _, _ -> false }
        onScrubChange = { false }
        onScrubFinished = { false }
    }

    private fun awaitInFlightCreates() {
        while (true) {
            val workers = synchronized(lifecycleLock) {
                createsInFlight.removeAll { worker -> !worker.isAlive }
                createsInFlight.toList()
            }
            if (workers.isEmpty()) return
            workers.forEach { worker -> awaitThreadCompletion(worker, "native create") }
        }
    }

    private fun awaitSwingCallbacksQueuedByCreates() {
        val completed = CountDownLatch(1)
        SwingUtilities.invokeLater { completed.countDown() }
        var interrupted = false
        while (true) {
            try {
                completed.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
            log.w { "interrupted while awaiting queued native create callbacks; completion was preserved" }
        }
    }

    private fun awaitThreadCompletion(thread: Thread?, operation: String) {
        if (thread == null || thread === Thread.currentThread()) return
        var interrupted = false
        while (thread.isAlive) {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
            log.w { "interrupted while awaiting $operation; completion was preserved" }
        }
    }

    private fun detachPlayerHandle(): Long = synchronized(lifecycleLock) {
        val current = handle
        handle = 0L
        lastSentControlsStructureKey = null
        current
    }

    private fun recordTerminalDisposeFailure(error: Throwable, operation: String, nativeHandle: Long) {
        synchronized(lifecycleLock) {
            terminalReleaseFailure = "Native player shutdown failed. Quit Nuvio before removing the player."
        }
        log.w(error) { "$operation dispose failed handle=$nativeHandle" }
    }

    /** Must be called while holding [lifecycleLock]. */
    private fun startTrackedDisposeLocked(current: Long) {
        val prior = disposeInFlight?.takeIf { it.isAlive }
        disposeInFlight = Thread(
            {
                awaitThreadCompletion(prior, "prior native teardown")
                runCatching { nativeDispose(current) }
                    .onFailure { error -> recordTerminalDisposeFailure(error, "native", current) }
            },
            "nuvio-player-dispose",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun disposePlayerHandle() {
        synchronized(lifecycleLock) {
            if (releaseRequested) return
            val current = handle
            if (current == 0L) return
            handle = 0L
            lastSentControlsStructureKey = null
            // Register and start teardown atomically so terminal release cannot sample an empty
            // worker slot between detaching the handle and starting native disposal.
            startTrackedDisposeLocked(current)
        }
    }

    override fun play() {
        log.d { "play handle=$handle" }
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setPaused(it, false) }
    }

    override fun pause() {
        log.d { "pause handle=$handle" }
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setPaused(it, true) }
    }

    override fun seekTo(positionMs: Long) {
        log.d { "seekTo positionMs=$positionMs handle=$handle" }
        handle.takeIf { it != 0L }?.let { nativeSeekTo(it, positionMs) }
    }

    override fun trySeekTo(positionMs: Long): Boolean {
        val current = handle.takeIf { it != 0L } ?: return false
        log.d { "trySeekTo positionMs=$positionMs handle=$current" }
        nativeSeekTo(current, positionMs)
        return true
    }

    override fun seekBy(offsetMs: Long) {
        log.d { "seekBy offsetMs=$offsetMs handle=$handle" }
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.seekBy(it, offsetMs) }
    }

    override fun retry() {
        val pending = pendingSource ?: return
        attach(
            sourceUrl = pending.sourceUrl,
            sourceHeaders = pending.headerLines.toHeaderMap(),
            playWhenReady = pending.playWhenReady,
            initialPositionMs = pending.initialPositionMs,
            decoderPriority = pending.decoderPriority,
            nvidiaRtxSuperResolutionEnabled = pending.nvidiaRtxSuperResolutionEnabled,
            onError = pending.onError,
        )
    }

    override fun setPlaybackSpeed(speed: Float) {
        log.d { "setPlaybackSpeed speed=$speed handle=$handle" }
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setSpeed(it, speed) }
    }

    override fun getAudioTracks(): List<AudioTrack> =
        decodeTracks { NativePlayerBridge.audioTracksJson(it) }.map { track ->
            AudioTrack(
                index = track.index,
                id = track.id,
                label = track.label,
                language = track.language.takeUnless(String::isBlank),
                isSelected = track.selected,
            )
        }

    override fun getSubtitleTracks(): List<SubtitleTrack> =
        decodeTracks { NativePlayerBridge.subtitleTracksJson(it) }.map { track ->
            SubtitleTrack(
                index = track.index,
                id = track.id,
                label = track.label,
                language = track.language.takeUnless(String::isBlank),
                isSelected = track.selected,
                isForced = track.forced || inferForcedSubtitleTrack(
                    label = track.label,
                    language = track.language,
                    trackId = track.id,
                ),
            )
        }

    override fun selectAudioTrack(index: Int) {
        val current = handle.takeIf { it != 0L } ?: return
        val tracks = decodeTracks { NativePlayerBridge.audioTracksJson(it) }
        val trackId = resolveTrackId(index, tracks) ?: run {
            log.w { "selectAudioTrack missing track index=$index count=${tracks.size} handle=$current" }
            return
        }
        log.d { "selectAudioTrack index=$index trackId=$trackId count=${tracks.size} handle=$current" }
        NativePlayerBridge.selectAudioTrack(current, trackId)
    }

    override fun selectSubtitleTrack(index: Int) {
        val current = handle.takeIf { it != 0L } ?: return
        if (index < 0) {
            log.d { "selectSubtitleTrack off handle=$current" }
            NativePlayerBridge.selectSubtitleTrack(current, -1)
            return
        }
        val tracks = decodeTracks { NativePlayerBridge.subtitleTracksJson(it) }
        val trackId = resolveTrackId(index, tracks) ?: run {
            log.w { "selectSubtitleTrack missing track index=$index count=${tracks.size} handle=$current" }
            return
        }
        log.d { "selectSubtitleTrack index=$index trackId=$trackId count=${tracks.size} handle=$current" }
        NativePlayerBridge.selectSubtitleTrack(current, trackId)
        applyPendingSubtitleSettings()
    }

    override fun setSubtitleUri(url: String) {
        log.d { "setSubtitleUri ${url.toPlaybackLogKey()} handle=$handle" }
        handle.takeIf { it != 0L }?.let { current ->
            NativePlayerBridge.clearExternalSubtitles(current)
            NativePlayerBridge.addSubtitleUrl(current, url)
        }
    }

    override fun clearExternalSubtitle() {
        log.d { "clearExternalSubtitle handle=$handle" }
        handle.takeIf { it != 0L }?.let(NativePlayerBridge::clearExternalSubtitles)
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        val current = handle.takeIf { it != 0L } ?: return
        val trackId = if (trackIndex < 0) {
            -1
        } else {
            val tracks = decodeTracks { NativePlayerBridge.subtitleTracksJson(it) }
            resolveTrackId(trackIndex, tracks) ?: run {
                log.w { "clearExternalSubtitleAndSelect missing track index=$trackIndex count=${tracks.size} handle=$current" }
                return
            }
        }
        log.d { "clearExternalSubtitleAndSelect trackIndex=$trackIndex trackId=$trackId handle=$current" }
        NativePlayerBridge.clearExternalSubtitlesAndSelect(current, trackId)
        applyPendingSubtitleSettings()
    }

    override fun setSubtitleDelayMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
        pendingSubtitleDelayMs = clamped
        handle.takeIf { it != 0L }?.let { current ->
            NativePlayerBridge.setSubtitleDelayMs(current, clamped)
        }
    }

    override fun applySubtitleStyle(style: SubtitleStyleState, useLibass: Boolean) {
        pendingSubtitleStyle = style
        pendingUseLibass = useLibass
        handle.takeIf { it != 0L }?.let { current ->
            applySubtitleStyle(current, style, useLibass)
        }
    }

    private fun applyPendingSubtitleSettings() {
        val current = handle.takeIf { it != 0L } ?: return
        pendingSubtitleDelayMs?.let { delayMs ->
            NativePlayerBridge.setSubtitleDelayMs(current, delayMs)
        }
        pendingSubtitleStyle?.let { style ->
            applySubtitleStyle(current, style, pendingUseLibass)
        }
    }

    private fun applySubtitleStyle(handle: Long, style: SubtitleStyleState, useLibass: Boolean) {
        NativePlayerBridge.applySubtitleStyle(
            handle = handle,
            textColor = style.textColor.toMpvColorString(),
            backgroundColor = style.backgroundColor.toMpvColorString(),
            outlineColor = style.outlineColor.toMpvColorString(),
            outlineSize = if (style.outlineEnabled) style.outlineWidth.toFloat() else 0f,
            bold = style.bold,
            fontSize = style.toMpvSubtitleFontSize(),
            subPos = style.toMpvSubtitlePosition(),
            useLibass = useLibass,
            stripSdh = style.stripSdh,
        )
    }

    private fun decodeTracks(readJson: (Long) -> String): List<NativeMpvTrack> {
        val current = handle.takeIf { it != 0L } ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<NativeMpvTrack>>(readJson(current))
        }.getOrDefault(emptyList())
    }
}

private fun String.toPlaybackLogKey(): String {
    val scheme = substringBefore(':', missingDelimiterValue = "unknown")
        .takeIf { it.isNotBlank() }
        ?: "unknown"
    return "scheme=$scheme length=$length hash=${hashCode()}"
}

private fun String.shouldLogNativeControlEvent(): Boolean {
    val normalized = lowercase()
    return normalized.contains("audio") ||
        normalized.contains("subtitle") ||
        normalized.contains("speed") ||
        normalized.contains("scrub") ||
        normalized.contains("seek") ||
        normalized.contains("episode") ||
        normalized == "resize" ||
        normalized == "toggle"
}

@Serializable
private data class NativeMpvTrack(
    val index: Int = 0,
    val id: String = "",
    val label: String = "",
    val language: String = "",
    val selected: Boolean = false,
    val forced: Boolean = false,
)

private fun resolveTrackId(index: Int, tracks: List<NativeMpvTrack>): Int? =
    tracks.firstNotNullOfOrNull { track ->
        if (track.index == index) {
            track.id.toIntOrNull()
        } else {
            null
        }
    } ?: tracks.getOrNull(index)?.id?.toIntOrNull()

private fun Color.toMpvColorString(): String {
    val alphaInt = (alpha * 255f).toInt().coerceIn(0, 255)
    val redInt = (red * 255f).toInt().coerceIn(0, 255)
    val greenInt = (green * 255f).toInt().coerceIn(0, 255)
    val blueInt = (blue * 255f).toInt().coerceIn(0, 255)
    return buildString {
        append('#')
        append(alphaInt.toHexByte())
        append(redInt.toHexByte())
        append(greenInt.toHexByte())
        append(blueInt.toHexByte())
    }
}

private fun SubtitleStyleState.toMpvSubtitlePosition(): Int =
    (100 - (bottomOffset / 2)).coerceIn(0, 150)

private fun SubtitleStyleState.toMpvSubtitleFontSize(): Float =
    (fontSizeSp * 3f).coerceIn(18f, 96f)

private fun Int.toHexByte(): String {
    val digits = "0123456789ABCDEF"
    val value = coerceIn(0, 255)
    return buildString {
        append(digits[value / 16])
        append(digits[value % 16])
    }
}

private data class PendingSource(
    val sourceUrl: String,
    val headerLines: List<String>,
    val playWhenReady: Boolean,
    val initialPositionMs: Long,
    val decoderPriority: Int,
    val nvidiaRtxSuperResolutionEnabled: Boolean,
    val onError: (String?) -> Unit,
)

private fun Map<String, String>.toHeaderLines(): List<String> =
    entries.mapNotNull { (key, value) ->
        val cleanKey = key.trim()
        val cleanValue = value.trim()
        if (cleanKey.isBlank() || cleanValue.isBlank()) {
            null
        } else {
            "$cleanKey: $cleanValue"
        }
    }

private fun List<String>.toHeaderMap(): Map<String, String> =
    mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        line.substring(0, separator).trim() to line.substring(separator + 1).trim()
    }.toMap()

private fun String.toPlayerControlsAction(): PlayerControlsAction? =
    when (this) {
        "toggleChrome" -> PlayerControlsAction.ToggleChrome
        "back" -> PlayerControlsAction.Back
        "toggle" -> PlayerControlsAction.TogglePlayback
        "keyboardToggle" -> PlayerControlsAction.KeyboardTogglePlayback
        "seekBack" -> PlayerControlsAction.SeekBack
        "keyboardSeekBack" -> PlayerControlsAction.KeyboardSeekBack
        "seekForward" -> PlayerControlsAction.SeekForward
        "keyboardSeekForward" -> PlayerControlsAction.KeyboardSeekForward
        "keyboardVolumeDown" -> PlayerControlsAction.KeyboardVolumeDown
        "keyboardVolumeUp" -> PlayerControlsAction.KeyboardVolumeUp
        "resize" -> PlayerControlsAction.ResizeMode
        "speed" -> PlayerControlsAction.Speed
        "subtitles" -> PlayerControlsAction.Subtitles
        "audio" -> PlayerControlsAction.Audio
        "sources" -> PlayerControlsAction.Sources
        "episodes" -> PlayerControlsAction.Episodes
        "external" -> PlayerControlsAction.OpenExternalPlayer
        "submitIntro" -> PlayerControlsAction.SubmitIntro
        "videoSettings" -> PlayerControlsAction.VideoSettings
        else -> null
    }

private data class NativeControlsStructureKey(
    val state: PlayerControlsState,
    val isFullscreen: Boolean,
)

private fun PlayerControlsState.toControlsJson(isFullscreen: Boolean): String =
    buildString {
        append('{')
        appendJsonField("title", title)
        append(',')
        appendJsonField("episodeText", episodeText)
        append(',')
        appendJsonField("streamTitle", streamTitle)
        append(',')
        appendJsonField("providerName", providerName)
        append(',')
        appendJsonField("pauseOverlayWatchingLabel", pauseOverlayWatchingLabel)
        append(',')
        appendJsonField("pauseOverlayLogo", pauseOverlayLogo.orEmpty())
        append(',')
        appendJsonField("pauseOverlayEpisodeInfo", pauseOverlayEpisodeInfo)
        append(',')
        appendJsonField("pauseOverlayEpisodeTitle", pauseOverlayEpisodeTitle)
        append(',')
        appendJsonField("pauseOverlayDescription", pauseOverlayDescription)
        append(',')
        appendJsonField("resizeModeLabel", resizeModeLabel)
        append(',')
        appendJsonField("playbackSpeedLabel", playbackSpeedLabel)
        append(',')
        appendJsonField("isFullscreen", isFullscreen)
        append(',')
        appendJsonField("volumeLevel", volumeLevel)
        append(',')
        appendJsonField("subtitlesLabel", subtitlesLabel)
        append(',')
        appendJsonField("audioLabel", audioLabel)
        append(',')
        appendJsonField("sourcesLabel", sourcesLabel)
        append(',')
        appendJsonField("episodesLabel", episodesLabel)
        append(',')
        appendJsonField("externalPlayerLabel", externalPlayerLabel)
        append(',')
        appendJsonField("playLabel", playLabel)
        append(',')
        appendJsonField("pauseLabel", pauseLabel)
        append(',')
        appendJsonField("closeLabel", closeLabel)
        append(',')
        appendJsonField("submitIntroLabel", submitIntroLabel)
        append(',')
        appendJsonField("videoSettingsLabel", videoSettingsLabel)
        append(',')
        appendJsonField("playbackErrorTitle", playbackErrorTitle)
        append(',')
        appendJsonField("playbackErrorMessage", playbackErrorMessage)
        append(',')
        appendJsonField("playbackErrorActionLabel", playbackErrorActionLabel)
        append(',')
        appendJsonField("sourcesPanelTitle", sourcesPanelTitle)
        append(',')
        appendJsonField("episodesPanelTitle", episodesPanelTitle)
        append(',')
        appendJsonField("streamsPanelTitle", streamsPanelTitle)
        append(',')
        appendJsonField("allFilterLabel", allFilterLabel)
        append(',')
        appendJsonField("reloadLabel", reloadLabel)
        append(',')
        appendJsonField("backLabel", backLabel)
        append(',')
        appendJsonField("panelCloseLabel", panelCloseLabel)
        append(',')
        appendJsonField("cancelLabel", cancelLabel)
        append(',')
        appendJsonField("playingLabel", playingLabel)
        append(',')
        appendJsonField("noStreamsLabel", noStreamsLabel)
        append(',')
        appendJsonField("noEpisodesLabel", noEpisodesLabel)
        append(',')
        appendJsonField("submitIntroPanelTitle", submitIntroPanelTitle)
        append(',')
        appendJsonField("submitIntroSegmentTypeLabel", submitIntroSegmentTypeLabel)
        append(',')
        appendJsonField("submitIntroSegmentIntroLabel", submitIntroSegmentIntroLabel)
        append(',')
        appendJsonField("submitIntroSegmentRecapLabel", submitIntroSegmentRecapLabel)
        append(',')
        appendJsonField("submitIntroSegmentOutroLabel", submitIntroSegmentOutroLabel)
        append(',')
        appendJsonField("submitIntroStartTimeLabel", submitIntroStartTimeLabel)
        append(',')
        appendJsonField("submitIntroEndTimeLabel", submitIntroEndTimeLabel)
        append(',')
        appendJsonField("submitIntroCaptureLabel", submitIntroCaptureLabel)
        append(',')
        appendJsonField("submitIntroSubmitLabel", submitIntroSubmitLabel)
        append(',')
        appendJsonField("p2pConsentTitle", p2pConsentTitle)
        append(',')
        appendJsonField("p2pConsentBody", p2pConsentBody)
        append(',')
        appendJsonField("p2pConsentEnableLabel", p2pConsentEnableLabel)
        append(',')
        appendJsonField("p2pConsentCancelLabel", p2pConsentCancelLabel)
        append(',')
        appendJsonField("speedPanelTitle", speedPanelTitle)
        append(',')
        appendJsonField("audioTracksPanelTitle", audioTracksPanelTitle)
        append(',')
        appendJsonField("noAudioTracksLabel", noAudioTracksLabel)
        append(',')
        appendJsonField("subtitlesPanelTitle", subtitlesPanelTitle)
        append(',')
        appendJsonField("subtitleLanguagesLabel", subtitleLanguagesLabel)
        append(',')
        appendJsonField("subtitleBuiltInTabLabel", subtitleBuiltInTabLabel)
        append(',')
        appendJsonField("subtitleAddonsTabLabel", subtitleAddonsTabLabel)
        append(',')
        appendJsonField("subtitleStyleTabLabel", subtitleStyleTabLabel)
        append(',')
        appendJsonField("customSubtitleStyleLabel", customSubtitleStyleLabel)
        append(',')
        appendJsonField("forcedLabel", forcedLabel)
        append(',')
        appendJsonField("noneLabel", noneLabel)
        append(',')
        appendJsonField("fetchSubtitlesLabel", fetchSubtitlesLabel)
        append(',')
        appendJsonField("subtitleDelayLabel", subtitleDelayLabel)
        append(',')
        appendJsonField("resetLabel", resetLabel)
        append(',')
        appendJsonField("autoSyncLabel", autoSyncLabel)
        append(',')
        appendJsonField("reloadSmallLabel", reloadSmallLabel)
        append(',')
        appendJsonField("captureLineLabel", captureLineLabel)
        append(',')
        appendJsonField("selectAddonSubtitleFirstLabel", selectAddonSubtitleFirstLabel)
        append(',')
        appendJsonField("loadingSubtitleLinesLabel", loadingSubtitleLinesLabel)
        append(',')
        appendJsonField("fontSizeLabel", fontSizeLabel)
        append(',')
        appendJsonField("outlineLabel", outlineLabel)
        append(',')
        appendJsonField("boldLabel", boldLabel)
        append(',')
        appendJsonField("bottomOffsetLabel", bottomOffsetLabel)
        append(',')
        appendJsonField("colorLabel", colorLabel)
        append(',')
        appendJsonField("textOpacityLabel", textOpacityLabel)
        append(',')
        appendJsonField("outlineColorLabel", outlineColorLabel)
        append(',')
        appendJsonField("noSubtitleLinesFoundLabel", noSubtitleLinesFoundLabel)
        append(',')
        appendJsonField("resetDefaultsLabel", resetDefaultsLabel)
        append(',')
        appendJsonField("onLabel", onLabel)
        append(',')
        appendJsonField("offLabel", offLabel)
        append(',')
        appendJsonField("themeAccentColor", themeAccentColor)
        append(',')
        appendJsonField("themeAccentStrongColor", themeAccentStrongColor)
        append(',')
        appendJsonField("themeOnAccentColor", themeOnAccentColor)
        append(',')
        appendJsonField("themeFocusColor", themeFocusColor)
        append(',')
        appendJsonField("themeSelectedSurfaceColor", themeSelectedSurfaceColor)
        append(',')
        appendJsonField("themeSelectedSurfaceHoverColor", themeSelectedSurfaceHoverColor)
        append(',')
        appendJsonField("themeSelectedRingColor", themeSelectedRingColor)
        append(',')
        appendJsonField("themeTimelineFillColor", themeTimelineFillColor)
        append(',')
        appendJsonField("themeTimelineTrackColor", themeTimelineTrackColor)
        append(',')
        appendJsonField("themeBufferingColor", themeBufferingColor)
        append(',')
        appendJsonField("themeBufferingTrackColor", themeBufferingTrackColor)
        append(',')
        appendJsonField("themeControlForegroundColor", themeControlForegroundColor)
        append(',')
        appendJsonField("themeSurfaceElevatedColor", themeSurfaceElevatedColor)
        append(',')
        appendJsonField("themeSurfaceCardColor", themeSurfaceCardColor)
        append(',')
        appendJsonField("themeSurfacePopoverColor", themeSurfacePopoverColor)
        append(',')
        appendJsonField("themeTextPrimaryColor", themeTextPrimaryColor)
        append(',')
        appendJsonField("themeTextSecondaryColor", themeTextSecondaryColor)
        append(',')
        appendJsonField("themeTextMutedColor", themeTextMutedColor)
        append(',')
        appendJsonField("themeBorderDefaultColor", themeBorderDefaultColor)
        append(',')
        appendJsonField("isPlaying", isPlaying)
        append(',')
        appendJsonField("isLoading", isLoading)
        append(',')
        appendJsonField("controlsVisible", controlsVisible)
        append(',')
        appendJsonArrayField("parentalWarnings", parentalWarnings) { appendParentalWarningJson(it) }
        append(',')
        appendJsonField("showParentalGuide", showParentalGuide)
        append(',')
        appendJsonField("showOpeningOverlay", showOpeningOverlay)
        append(',')
        appendJsonField("openingArtwork", openingArtwork.orEmpty())
        append(',')
        appendJsonField("openingLogo", openingLogo.orEmpty())
        append(',')
        appendJsonField("openingTitle", openingTitle)
        append(',')
        appendJsonField("openingMessage", openingMessage.orEmpty())
        append(',')
        appendJsonField("openingProgress", openingProgress)
        append(',')
        appendJsonField("skipPromptVisible", skipPromptVisible)
        append(',')
        appendJsonField("skipPromptLabel", skipPromptLabel)
        append(',')
        appendJsonField("skipPromptStartMs", skipPromptStartMs)
        append(',')
        appendJsonField("skipPromptEndMs", skipPromptEndMs)
        append(',')
        appendJsonField("skipPromptDismissed", skipPromptDismissed)
        append(',')
        appendJsonField("nextEpisodeVisible", nextEpisodeVisible)
        append(',')
        appendJsonField("nextEpisodeHeaderLabel", nextEpisodeHeaderLabel)
        append(',')
        appendJsonField("nextEpisodeTitle", nextEpisodeTitle)
        append(',')
        appendJsonField("nextEpisodeThumbnail", nextEpisodeThumbnail)
        append(',')
        appendJsonField("nextEpisodeStatus", nextEpisodeStatus)
        append(',')
        appendJsonField("nextEpisodeActionLabel", nextEpisodeActionLabel)
        append(',')
        appendJsonField("nextEpisodePlayable", nextEpisodePlayable)
        append(',')
        appendJsonField("showSubmitIntro", showSubmitIntro)
        append(',')
        appendJsonField("showVideoSettings", showVideoSettings)
        append(',')
        appendJsonField("showSources", showSources)
        append(',')
        appendJsonField("showEpisodes", showEpisodes)
        append(',')
        appendJsonField("showExternalPlayer", showExternalPlayer)
        append(',')
        appendJsonField("durationMs", durationMs)
        append(',')
        appendJsonField("positionMs", positionMs)
        append(',')
        appendJsonField("sourceIsLoading", sourceIsLoading)
        append(',')
        appendJsonArrayField("sourceFilters", sourceFilters) { appendFilterItemJson(it) }
        append(',')
        appendJsonArrayField("sourceItems", sourceItems) { appendSourceItemJson(it) }
        append(',')
        appendJsonArrayField("episodeItems", episodeItems) { appendEpisodeItemJson(it) }
        append(',')
        appendJsonArrayField("episodeSeasons", episodeSeasons) { appendSeasonItemJson(it) }
        append(',')
        appendJsonField("episodeStreamsVisible", episodeStreamsVisible)
        append(',')
        appendJsonField("episodeStreamsIsLoading", episodeStreamsIsLoading)
        append(',')
        appendJsonField("selectedEpisodeLabel", selectedEpisodeLabel)
        append(',')
        appendJsonArrayField("episodeStreamFilters", episodeStreamFilters) { appendFilterItemJson(it) }
        append(',')
        appendJsonArrayField("episodeStreamItems", episodeStreamItems) { appendSourceItemJson(it) }
        append(',')
        appendJsonField("blurUnwatchedEpisodes", blurUnwatchedEpisodes)
        append(',')
        appendJsonField("submitIntroSegmentType", submitIntroSegmentType)
        append(',')
        appendJsonField("submitIntroContentKey", submitIntroContentKey)
        append(',')
        appendJsonField("submitIntroStartTime", submitIntroStartTime)
        append(',')
        appendJsonField("submitIntroEndTime", submitIntroEndTime)
        append(',')
        appendJsonField("isSubmitIntroSubmitting", isSubmitIntroSubmitting)
        append(',')
        appendJsonField("submitIntroStatusMessage", submitIntroStatusMessage)
        append(',')
        appendJsonField("showP2pConsent", showP2pConsent)
        append(',')
        appendJsonField("subtitleActiveTab", subtitleActiveTab)
        append(',')
        appendJsonArrayField("subtitleLanguageItems", subtitleLanguageItems) { appendSubtitleLanguageItemJson(it) }
        append(',')
        appendJsonArrayField("subtitleOptionItems", subtitleOptionItems) { appendSubtitleOptionItemJson(it) }
        append(',')
        appendJsonField("selectedSubtitleLanguageKey", selectedSubtitleLanguageKey)
        append(',')
        appendJsonField("selectedSubtitleOptionId", selectedSubtitleOptionId)
        append(',')
        appendJsonArrayField("addonSubtitleItems", addonSubtitleItems) { appendAddonSubtitleItemJson(it) }
        append(',')
        appendJsonField("isLoadingAddonSubtitles", isLoadingAddonSubtitles)
        append(',')
        appendJsonField("selectedAddonSubtitleId", selectedAddonSubtitleId)
        append(',')
        appendJsonField("useCustomSubtitles", useCustomSubtitles)
        append(',')
        appendJsonField("customSubtitleStylingEnabled", customSubtitleStylingEnabled)
        append(',')
        appendJsonField("subtitleDelayMs", subtitleDelayMs)
        append(',')
        appendJsonField("hasSelectedAddonSubtitle", hasSelectedAddonSubtitle)
        append(',')
        appendJsonField("subtitleAutoSyncCapturedPositionMs", subtitleAutoSyncCapturedPositionMs)
        append(',')
        appendJsonArrayField("subtitleAutoSyncCues", subtitleAutoSyncCues) { appendSubtitleCueItemJson(it) }
        append(',')
        appendJsonField("subtitleAutoSyncIsLoading", subtitleAutoSyncIsLoading)
        append(',')
        appendJsonField("subtitleAutoSyncErrorMessage", subtitleAutoSyncErrorMessage)
        append(',')
        appendJsonField("subtitleStyle", subtitleStyle)
        append(',')
        appendJsonArrayField("subtitleColorSwatches", SubtitleColorSwatches.map { it.toStorageHexString() }) { append(it.toJsonString()) }
        append(',')
        appendJsonArrayField("subtitleOutlineColorSwatches", SubtitleOutlineColorSwatches.map { it.toStorageHexString() }) { append(it.toJsonString()) }
        append(',')
        appendJsonField("closeModalsToken", closeModalsToken)
        append(',')
        appendJsonField("submitIntroSuccessToken", submitIntroSuccessToken)
        append(',')
        appendJsonField("notificationMessage", notificationMessage)
        append(',')
        appendJsonField("notificationToken", notificationToken)
        append('}')
    }

private fun PlayerControlsState.nativeControlsStructureKey(): PlayerControlsState =
    copy(
        isPlaying = false,
        isLoading = false,
        durationMs = 0L,
        positionMs = 0L,
    )

private fun StringBuilder.appendJsonField(name: String, value: String) {
    append('"').append(name).append("\":")
    append(value.toJsonString())
}

private fun StringBuilder.appendJsonField(name: String, value: Boolean) {
    append('"').append(name).append("\":").append(value)
}

private fun StringBuilder.appendJsonField(name: String, value: Long) {
    append('"').append(name).append("\":").append(value)
}

private fun StringBuilder.appendJsonField(name: String, value: Float?) {
    append('"').append(name).append("\":")
    if (value == null || value.isNaN() || value.isInfinite()) {
        append("null")
    } else {
        append(value.coerceIn(0f, 1f))
    }
}

private fun StringBuilder.appendJsonField(name: String, value: Int) {
    append('"').append(name).append("\":").append(value)
}

private fun StringBuilder.appendJsonField(name: String, value: SubtitleStyleState) {
    append('"').append(name).append("\":")
    appendSubtitleStyleJson(value)
}

private inline fun <T> StringBuilder.appendJsonArrayField(
    name: String,
    values: List<T>,
    appendValue: StringBuilder.(T) -> Unit,
) {
    append('"').append(name).append("\":[")
    values.forEachIndexed { index, value ->
        if (index > 0) append(',')
        appendValue(value)
    }
    append(']')
}

private fun StringBuilder.appendFilterItemJson(item: PlayerControlFilterItem) {
    append('{')
    appendJsonField("id", item.id)
    append(',')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("isSelected", item.isSelected)
    append(',')
    appendJsonField("isLoading", item.isLoading)
    append(',')
    appendJsonField("hasError", item.hasError)
    append('}')
}

private fun StringBuilder.appendSeasonItemJson(item: PlayerControlSeasonItem) {
    append('{')
    appendJsonField("season", item.season)
    append(',')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("isSelected", item.isSelected)
    append('}')
}

private fun StringBuilder.appendSourceItemJson(item: PlayerControlSourceItem) {
    append('{')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("filterId", item.filterId)
    append(',')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("subtitle", item.subtitle)
    append(',')
    appendJsonField("addonName", item.addonName)
    append(',')
    appendJsonField("addonLogo", item.addonLogo)
    append(',')
    appendJsonField("showAddonLogo", item.showAddonLogo)
    append(',')
    appendJsonField("isCurrent", item.isCurrent)
    append(',')
    appendJsonField("isEnabled", item.isEnabled)
    append(',')
    appendJsonField("formattedSize", item.formattedSize)
    append(',')
    appendJsonField("badgePlacement", item.badgePlacement)
    append(',')
    appendJsonArrayField("badges", item.badges) { badge ->
        append('{')
        appendJsonField("name", badge.name)
        append(',')
        appendJsonField("imageURL", badge.imageURL)
        append(',')
        appendJsonField("tagColor", badge.tagColor)
        append(',')
        appendJsonField("tagStyle", badge.tagStyle)
        append(',')
        appendJsonField("borderColor", badge.borderColor)
        append('}')
    }
    append('}')
}

private fun StringBuilder.appendEpisodeItemJson(item: PlayerControlEpisodeItem) {
    append('{')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("id", item.id)
    append(',')
    appendJsonField("title", item.title)
    append(',')
    appendJsonField("code", item.code)
    append(',')
    appendJsonField("overview", item.overview)
    append(',')
    appendJsonField("thumbnail", item.thumbnail)
    append(',')
    appendJsonField("released", item.released)
    append(',')
    appendJsonField("season", item.season)
    append(',')
    appendJsonField("episode", item.episode)
    append(',')
    appendJsonField("isCurrent", item.isCurrent)
    append(',')
    appendJsonField("isWatched", item.isWatched)
    append('}')
}

private fun StringBuilder.appendAddonSubtitleItemJson(item: PlayerControlAddonSubtitleItem) {
    append('{')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("id", item.id)
    append(',')
    appendJsonField("display", item.display)
    append(',')
    appendJsonField("language", item.language)
    append(',')
    appendJsonField("languageLabel", item.languageLabel)
    append(',')
    appendJsonField("addonName", item.addonName)
    append(',')
    appendJsonField("isSelected", item.isSelected)
    append('}')
}

private fun StringBuilder.appendSubtitleLanguageItemJson(item: PlayerControlSubtitleLanguageItem) {
    append('{')
    appendJsonField("key", item.key)
    append(',')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("count", item.count)
    append(',')
    appendJsonField("isSelected", item.isSelected)
    append('}')
}

private fun StringBuilder.appendSubtitleOptionItemJson(item: PlayerControlSubtitleOptionItem) {
    append('{')
    appendJsonField("id", item.id)
    append(',')
    appendJsonField("languageKey", item.languageKey)
    append(',')
    appendJsonField("kind", item.kind)
    append(',')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("sourceLabel", item.sourceLabel)
    append(',')
    appendJsonField("title", item.title)
    append(',')
    appendJsonField("metadata", item.metadata)
    append(',')
    appendJsonField("isSelected", item.isSelected)
    append('}')
}

private fun StringBuilder.appendSubtitleCueItemJson(item: PlayerControlSubtitleCueItem) {
    append('{')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("timeMs", item.timeMs)
    append(',')
    appendJsonField("timeLabel", item.timeLabel)
    append(',')
    appendJsonField("text", item.text)
    append('}')
}

private fun StringBuilder.appendParentalWarningJson(item: ParentalWarning) {
    append('{')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("severity", item.severity)
    append('}')
}

private fun StringBuilder.appendSubtitleStyleJson(style: SubtitleStyleState) {
    append('{')
    appendJsonField("textColor", style.textColor.toStorageHexString())
    append(',')
    appendJsonField("outlineColor", style.outlineColor.toStorageHexString())
    append(',')
    appendJsonField("outlineEnabled", style.outlineEnabled)
    append(',')
    appendJsonField("bold", style.bold)
    append(',')
    appendJsonField("fontSizeSp", style.fontSizeSp)
    append(',')
    appendJsonField("bottomOffset", style.bottomOffset)
    append('}')
}

private fun String.toJsonString(): String =
    buildString(length + 2) {
        append('"')
        for (char in this@toJsonString) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }
