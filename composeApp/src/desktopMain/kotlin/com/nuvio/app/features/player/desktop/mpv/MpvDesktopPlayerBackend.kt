package com.nuvio.app.features.player.desktop.mpv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.nuvio.app.desktop.DesktopPlayerRegistry
import com.nuvio.app.desktop.DesktopRuntimeLog
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.PlayerAudioLevel
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.desktop.DesktopPlayerBackend
import com.nuvio.app.features.player.desktop.DesktopPlayerError
import com.nuvio.app.features.player.desktop.DesktopPlayerPhase
import com.nuvio.app.features.player.desktop.DesktopPlayerRequest
import com.nuvio.app.features.player.desktop.DesktopPlayerState
import com.nuvio.app.features.player.desktop.LinuxDisplayWakeLock
import com.nuvio.app.features.player.desktop.MacOsDisplayWakeLock
import com.nuvio.app.features.player.desktop.WindowsDisplayWakeLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.source.UriMediaData
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext

private const val ExternalSubtitleCodepage = "+utf-8"
private const val EmbeddedSubtitleCodepage = "auto"
private const val ExternalSubtitleAssOverride = "strip"
private const val EmbeddedSubtitleAssOverride = "no"
private const val WatchdogFrozenMs = 3_000L
private const val WatchdogCooldownMs = 15_000L
private const val WatchdogSkipSec = 5.0
private const val WatchdogMinPositionMs = 10_000L
private const val WatchdogTickMs = 500L
private const val WatchdogBufferingStuckMs = 30_000L
private const val WatchdogMarkerV9 = "NUVIO_BUILD_MARKER_LINUX_2026_06_06_PAUSE_RESUME_FIX_V9"

@OptIn(InternalMediampApi::class)
internal class MpvDesktopPlayerBackend private constructor(
    private val runtime: MpvRuntimeResolution,
    private val player: MpvMediampPlayer,
) : DesktopPlayerBackend {
    override val id: String = "windows-mpv-${System.identityHashCode(player)}"
    override val backendName: String = "windows-mediamp-mpv"
    private val mpvHandle: MPVHandle get() = player.impl as MPVHandle

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateFlow = MutableStateFlow(
        DesktopPlayerState(
            phase = DesktopPlayerPhase.Idle,
            backendName = backendName,
            diagnostics = runtime.diagnostics,
        ),
    )

    @Volatile private var stopped = false
    @Volatile private var nativeClosed = false
    @Volatile private var currentRequest: DesktopPlayerRequest? = null
    @Volatile private var lastKnownPositionMs: Long = 0L
    @Volatile private var pendingSeekMs: Long = 0L
    @Volatile private var lastPauseAtMs: Long = 0L
    private val reconnectStaleMs = 30_000L
    @Volatile private var watchdogLastPositionMs: Long = -1L
    @Volatile private var watchdogLastChangeAtMs: Long = 0L
    @Volatile private var watchdogRecoveryCount: Int = 0
    @Volatile private var watchdogLastRecoveryAtMs: Long = 0L
    @Volatile private var watchdogBufferingStartAtMs: Long = 0L
    @Volatile private var externalSubtitleActive = false
    @Volatile private var displayWakeLockHeld = false
    @Volatile private var latestSubtitleStyle = SubtitleStyleState.DEFAULT
    private val framePacingSamples = ArrayDeque<String>()
    private val externalSubtitleRequestCounter = AtomicInteger(0)
    private val externalSubtitleTempFiles = mutableSetOf<Path>()
    private val subtitleHttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override val state: StateFlow<DesktopPlayerState> = stateFlow
    override val controller: PlayerEngineController = MpvController()

    init {
        observePlayerState()
        observeFramePacing()
        observePlaybackSettings()
        applyDecoderSettings()
        applyCursorSettings()
        startFreezeWatchdog()
        val initialTuning = loadDesktopMpvVideoTuning()
        val effectiveHwdec = if (isOsLinux() &&
            System.getProperty("nuvio.mpv.diagnostic.hwdec") == null &&
            System.getenv("NUVIO_MPV_DIAGNOSTIC_HWDEC") == null
        ) "no(linux-forced)" else initialTuning.settings.hardwareDecoderMode.mpvValue
        val marker = WatchdogMarkerV9
        DesktopRuntimeLog.info("$marker $marker $marker")
        DesktopRuntimeLog.info(
            "MPV backend init os=${System.getProperty("os.name")} " +
                "hwdecMode=${initialTuning.settings.hardwareDecoderMode} " +
                "effectiveHwdec=$effectiveHwdec",
        )
        DesktopRuntimeLog.info("MPV backend created id=$id runtime=${runtime.directory?.safePath() ?: "none"}")
        System.err.println("[$marker] effectiveHwdec=$effectiveHwdec freezeWatchdog=enabled tickMs=$WatchdogTickMs frozenMsThreshold=$WatchdogFrozenMs skipSec=$WatchdogSkipSec cooldownMs=$WatchdogCooldownMs minPositionMs=$WatchdogMinPositionMs")
        System.err.flush()
    }

    override suspend fun load(request: DesktopPlayerRequest) {
        System.err.println("[mediamp-debug] MpvDesktopPlayerBackend.load() sourceUrl=${request.sourceUrl} playWhenReady=${request.playWhenReady} seekTargetMs=${request.seekTargetMs}")
        if (nativeClosed) { System.err.println("[mediamp-debug] load SKIP: nativeClosed"); return }
        if (request.sourceUrl.isBlank()) {
            System.err.println("[mediamp-debug] load FAIL: blank source URL")
            fail(DesktopPlayerError.InvalidSource(backendName, "Blank source URL"))
            return
        }
        if (currentRequest != null) {
            emitFramePacingSummary("load")
        }
        currentRequest = request
        stopped = false
        stateFlow.value = stateFlow.value.copy(phase = DesktopPlayerPhase.Preparing, error = null)
        applyHwdecAtLoad()
        applyHlsSpecificOptionsIfNeeded(request.sourceUrl)
        runCatching {
            val headers = request.sourceHeaders.toMutableMap()
            val resolvedUrl = request.sourceUrl
            DesktopRuntimeLog.info(
                "MPV load start session=${request.sessionKey} source=${resolvedUrl.redactedMediaUrl()} " +
                    "audio=${request.sourceAudioUrl?.redactedMediaUrl() ?: "none"} headersPresent=${headers.isNotEmpty()}" +
                    if (resolvedUrl != request.sourceUrl) " (pre-resolved)" else "",
            )
            resetExternalSubtitleState("load")
            player.setMediaData(UriMediaData(resolvedUrl, headers))
            if (request.seekTargetMs > 0L) {
                pendingSeekMs = request.seekTargetMs
                DesktopRuntimeLog.info("MPV deferred seek target=${request.seekTargetMs}ms")
            } else {
                pendingSeekMs = 0L
            }
            request.sourceAudioUrl?.takeIf { it.isNotBlank() }?.let { audioUrl ->
                runCatching { mpvHandle.command("audio-add", audioUrl, "auto") }
                    .onFailure { DesktopRuntimeLog.error("MPV audio-add failed audio=${audioUrl.redactedMediaUrl()}", it) }
            }
            setResizeMode(request.resizeMode)
            if (request.playWhenReady) {
                player.resume()
                runCatching { mpvHandle.setPropertyBoolean("pause", false) }
                    .onFailure { DesktopRuntimeLog.error("MPV unpause after load failed", it) }
            } else {
                player.pause()
            }
            DesktopRuntimeLog.info("MPV load success session=${request.sessionKey}")
        }.onFailure { throwable ->
            DesktopRuntimeLog.error("MPV load failed source=${request.sourceUrl.redactedMediaUrl()}", throwable)
            releaseDisplayWakeLock("load-failed")
            fail(DesktopPlayerError.MediaLoadFailed(backendName, "MPV media load failed", throwable))
        }
    }

    override fun setResizeMode(resizeMode: PlayerResizeMode) {
        if (!canReceiveCommands()) return
        runCatching { mpvHandle.applyResizeMode(resizeMode) }
            .onSuccess { DesktopRuntimeLog.info("MPV resizeMode=$resizeMode applied") }
            .onFailure { DesktopRuntimeLog.error("MPV resizeMode=$resizeMode failed", it) }
    }

    override fun releaseSoft() {
        if (stopped) return
        stopped = true
        DesktopRuntimeLog.info("MPV releaseSoft id=$id")
        emitFramePacingSummary("releaseSoft")
        releaseDisplayWakeLock("releaseSoft")
        resetExternalSubtitleState("releaseSoft")
        runCatching { mpvHandle.setPropertyBoolean("mute", true) }
        runCatching { mpvHandle.command("stop") }
            .onFailure { DesktopRuntimeLog.error("MPV stop failed id=$id", it) }
        stateFlow.value = stateFlow.value.copy(phase = DesktopPlayerPhase.Closed)
    }

    override fun close() {
        if (nativeClosed) return
        emitFramePacingSummary("close")
        releaseDisplayWakeLock("close")
        resetExternalSubtitleState("close")
        nativeClosed = true
        scope.cancel()
        DesktopRuntimeLog.info("MPV close async id=$id")
        val thread = Thread({
            val startMs = System.currentTimeMillis()
            runCatching { player.close() }
                .onSuccess {
                    DesktopRuntimeLog.info("MPV native close done id=$id elapsedMs=${System.currentTimeMillis() - startMs}")
                }
                .onFailure { DesktopRuntimeLog.error("MPV native close failed id=$id", it) }
        }, "mpv-close-$id").apply { isDaemon = true }
        DesktopPlayerRegistry.trackCloseThread(thread)
        thread.start()
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        MpvDesktopPlayerSurface(player = player, modifier = modifier)
    }

    private fun observePlayerState() {
        combine(
            player.playbackState,
            player.currentPositionMillis,
            player.mediaProperties,
        ) { playbackState, position, props ->
            val phase = playbackState.toDesktopPhase()
            DesktopPlayerState(
                phase = phase,
                positionMs = position,
                durationMs = props?.durationMillis?.takeIf { it > 0 } ?: 0L,
                bufferedPositionMs = 0L,
                playbackSpeed = player.features[PlaybackSpeed]?.value ?: 1.0f,
                backendName = backendName,
                diagnostics = runtime.diagnostics,
                error = if (playbackState == PlaybackState.ERROR) {
                    val mpvError = player.lastMpvError
                    val msg = mpvError ?: "MPV playback state is ERROR"
                    DesktopPlayerError.PlaybackFailed(
                        backendName = backendName,
                        technicalMessage = "MPV: $msg",
                        uiMessage = msg,
                    )
                } else {
                    null
                },
            )
        }.onEach { mapped ->
            if (mapped.phase == DesktopPlayerPhase.Playing && mapped.positionMs > 0L) {
                lastKnownPositionMs = mapped.positionMs
            }
            if (pendingSeekMs > 0L && mapped.durationMs > 0L) {
                val seekMs = pendingSeekMs
                pendingSeekMs = 0L
                val seekSec = seekMs / 1000.0
                val ok = runCatching { mpvHandle.command("seek", seekSec.toString(), "absolute") }
                    .getOrNull() == true
                if (ok) {
                    player.currentPositionMillis.value = seekMs
                }
                DesktopRuntimeLog.info("MPV seek after load executed target=${seekMs}ms ok=$ok duration=${mapped.durationMs}")
            }
            if (!nativeClosed) {
                updateDisplayWakeLock(mapped.phase)
                stateFlow.value = mapped
                DesktopRuntimeLog.info("[WP-STATE] phase=${mapped.phase} pos=${mapped.positionMs}ms dur=${mapped.durationMs}ms")
            }
        }.launchIn(scope)
    }

    private fun startFreezeWatchdog() {
        scope.launch {
            while (!nativeClosed) {
                delay(WatchdogTickMs)
                if (stopped || nativeClosed) continue
                val phase = player.playbackState.value.toDesktopPhase()
                val now = System.currentTimeMillis()
                if (phase != DesktopPlayerPhase.Playing) {
                    watchdogLastPositionMs = -1L
                    watchdogLastChangeAtMs = 0L
                    watchdogBufferingStartAtMs = 0L
                    continue
                }
                val currentPos = player.currentPositionMillis.value
                if (currentPos != watchdogLastPositionMs) {
                    watchdogLastPositionMs = currentPos
                    watchdogLastChangeAtMs = now
                    watchdogBufferingStartAtMs = 0L
                    continue
                }
                val frozenFor = now - watchdogLastChangeAtMs
                if (frozenFor < WatchdogFrozenMs) continue
                if (currentPos < WatchdogMinPositionMs) continue
                val isBuffering = runCatching {
                    mpvHandle.getMpvBooleanProperty("paused-for-cache")
                }.getOrDefault(false)
                if (isBuffering) {
                    if (watchdogBufferingStartAtMs == 0L) {
                        watchdogBufferingStartAtMs = now
                    }
                    val bufferingFor = now - watchdogBufferingStartAtMs
                    if (bufferingFor < WatchdogBufferingStuckMs) {
                        DesktopRuntimeLog.info("MPV watchdog: paused-for-cache pos=${currentPos}ms frozenFor=${frozenFor}ms bufferingFor=${bufferingFor}ms (waiting)")
                        watchdogLastChangeAtMs = now
                        continue
                    }
                    DesktopRuntimeLog.info("MPV watchdog: paused-for-cache STUCK for ${bufferingFor}ms -> forcing recovery")
                }
                if (now - watchdogLastRecoveryAtMs < WatchdogCooldownMs) continue
                watchdogLastRecoveryAtMs = now
                watchdogRecoveryCount += 1
                val skipMs = (WatchdogSkipSec * 1000.0).toLong()
                val targetPos = currentPos + skipMs
                System.err.println("[$WatchdogMarkerV9] AUTO-RECOVERY: time-pos frozen for ${frozenFor}ms at pos=${currentPos}ms (recovery #${watchdogRecoveryCount}). Seeking +${WatchdogSkipSec}s to ${targetPos}ms.")
                System.err.flush()
                val seekOk = runCatching {
                    mpvHandle.command("seek", WatchdogSkipSec.toString(), "relative")
                }.onFailure {
                    DesktopRuntimeLog.error("MPV auto-recovery seek+${WatchdogSkipSec}s failed", it)
                }.getOrNull() == true
                if (seekOk) {
                    DesktopRuntimeLog.info("MPV auto-recovery seek+${WatchdogSkipSec}s fired count=$watchdogRecoveryCount at pos=${currentPos}ms targetPos=${targetPos}ms")
                    watchdogLastPositionMs = targetPos
                    watchdogLastChangeAtMs = now
                    watchdogBufferingStartAtMs = 0L
                }
            }
        }
    }

    private fun observeFramePacing() {
        scope.launch {
            while (!nativeClosed) {
                delay(2_000)
                if (!DesktopRuntimeLog.debugEnabled || nativeClosed) continue
                if (stateFlow.value.phase != DesktopPlayerPhase.Playing) continue
                collectFramePacingSample()?.let { sample ->
                    synchronized(framePacingSamples) {
                        framePacingSamples.addLast(sample)
                        while (framePacingSamples.size > 30) {
                            framePacingSamples.removeFirst()
                        }
                    }
                    DesktopRuntimeLog.info("MPV framePacing sample $sample")
                }
            }
        }
    }

    private fun collectFramePacingSample(): String? =
        runCatching {
            val state = stateFlow.value
            "phase=${state.phase} pos=${state.positionMs}ms " +
                "vfFps=${mpvHandle.getMpvStringPropertyOrNull("estimated-vf-fps") ?: "n/a"} " +
                "estimatedFrames=${mpvHandle.getMpvStringPropertyOrNull("estimated-frame-count") ?: "n/a"} " +
                "voDropped=${mpvHandle.getMpvStringPropertyOrNull("frame-drop-count") ?: "n/a"} " +
                "decoderDropped=${mpvHandle.getMpvStringPropertyOrNull("decoder-frame-drop-count") ?: "n/a"} " +
                "mistimed=${mpvHandle.getMpvStringPropertyOrNull("mistimed-frame-count") ?: "n/a"} " +
                "delayed=${mpvHandle.getMpvStringPropertyOrNull("vo-delayed-frame-count") ?: "n/a"} " +
                "displaySync=${mpvHandle.getMpvStringPropertyOrNull("display-sync-active") ?: "n/a"} " +
                "avsync=${mpvHandle.getMpvStringPropertyOrNull("avsync") ?: "n/a"}"
        }.onFailure {
            DesktopRuntimeLog.warn("MPV framePacing sample failed message=${it.message}")
        }.getOrNull()

    private fun emitFramePacingSummary(reason: String) {
        if (!DesktopRuntimeLog.debugEnabled) return
        val samples = synchronized(framePacingSamples) {
            framePacingSamples.toList().also { framePacingSamples.clear() }
        }
        if (samples.isEmpty()) return
        DesktopRuntimeLog.info(
            "MPV framePacing summary reason=$reason sampleCount=${samples.size} " +
                "latest=${samples.takeLast(5).joinToString(separator = " | ")}",
        )
    }

    private fun observePlaybackSettings() {
        DesktopMpvPlaybackSettingsSignal.version
            .drop(1)
            .onEach {
                if (!nativeClosed) {
                    applyDecoderSettings()
                }
            }
            .launchIn(scope)
    }

    private fun updateDisplayWakeLock(phase: DesktopPlayerPhase) {
        if (phase == DesktopPlayerPhase.Playing) {
            if (!displayWakeLockHeld) {
                displayWakeLockHeld = true
                val reason = "$backendName:$id:$phase"
                WindowsDisplayWakeLock.acquire(reason)
                LinuxDisplayWakeLock.acquire(reason)
                MacOsDisplayWakeLock.acquire(reason)
            }
        } else {
            releaseDisplayWakeLock("phase-$phase")
        }
    }

    private fun releaseDisplayWakeLock(reason: String) {
        if (!displayWakeLockHeld) return
        displayWakeLockHeld = false
        val msg = "$backendName:$id:$reason"
        WindowsDisplayWakeLock.release(msg)
        LinuxDisplayWakeLock.release(msg)
        MacOsDisplayWakeLock.release(msg)
    }

    private fun applyDecoderSettings() {
        if (nativeClosed) return
        val tuning = loadDesktopMpvVideoTuning()
        val options = mpvRuntimeOptions(tuning)
        val appliedOptions = mutableListOf<String>()
        val skippedOptions = mutableListOf<String>()

        options.forEach { option ->
            if (option.name == "hwdec") {
                skippedOptions += "${option.name}=${option.value} (deferred to next load)"
                return@forEach
            }
            runCatching {
                mpvHandle.setMpvRuntimeOption(option.name, option.value)
            }.onSuccess { applied ->
                val entry = "${option.name}=${option.value}"
                if (applied) {
                    appliedOptions += entry
                } else {
                    skippedOptions += entry
                }
            }.onFailure {
                DesktopRuntimeLog.warn(
                    "MPV video tuning: failed preset=${tuning.settings.outputPreset} " +
                        "${option.name}=${option.value} message=${it.message}",
                )
            }
        }

        DesktopRuntimeLog.info(
            "MPV video tuning: preset=${tuning.settings.outputPreset} legacyHdr=${tuning.legacyHdrMode.storageValue} " +
                "applied=${appliedOptions.size}/${options.size} skipped=${skippedOptions.size} " +
                "options=${appliedOptions.joinToString(",")}" +
                if (skippedOptions.isNotEmpty()) " skippedOptions=${skippedOptions.joinToString(",")}" else "",
        )
    }

    private fun applyHwdecAtLoad() {
        val hwdecTuning = loadDesktopMpvVideoTuning()
        val targets = mpvRuntimeOptions(hwdecTuning).filter { it.name == "hwdec" }
        targets.forEach { option ->
            val applied = runCatching { mpvHandle.setMpvRuntimeOption(option.name, option.value) }
                .onFailure { DesktopRuntimeLog.warn("MPV hwdec apply at load failed message=${it.message}") }
                .getOrDefault(false)
            val actual = runCatching { mpvHandle.getMpvStringPropertyOrNull("hwdec") }.getOrNull() ?: "<unknown>"
            DesktopRuntimeLog.info("MPV hwdec apply target=${option.value} applied=$applied actual=$actual")
        }
    }

    private fun applyHlsSpecificOptionsIfNeeded(sourceUrl: String) {
        if (nativeClosed) return
        val isHls = sourceUrl.contains(".m3u8", ignoreCase = true) ||
            sourceUrl.contains("m3u8", ignoreCase = true)
        if (!isHls) return
        val overrides = listOf(
            "cache" to "yes",
            "cache-pause" to "yes",
            "demuxer-cache-secs" to "10",
            "force-seekable" to "no",
            "errordetect" to "ignore_err",
            "framedrop" to "decoder+vo",
            "vd-lavc-fast" to "yes",
            "vd-lavc-err-detect" to "ignore_err",
            "vd-lavc-skiploopfilter" to "nonkey",
            "vd-extra-frames" to "8",
            "demuxer-lavf-o" to "fflags=+flush_packets+genpts+discardcorrupt",
        )
        val applied = mutableListOf<String>()
        overrides.forEach { (name, value) ->
            val ok = runCatching { mpvHandle.setMpvRuntimeOption(name, value) }
                .onFailure { DesktopRuntimeLog.warn("MPV HLS override $name=$value failed message=${it.message}") }
                .getOrDefault(false)
            if (ok) applied.add("$name=$value")
        }
        DesktopRuntimeLog.info("MPV HLS overrides applied=${applied.joinToString(",")}")
        System.err.println("[$WatchdogMarkerV9] HLS overrides applied=${applied.joinToString(",")}")
        System.err.flush()
    }

    private fun applyCursorSettings() {
        if (nativeClosed) return
        runCatching {
            mpvHandle.setMpvRuntimeOption("cursor-autohide", "1000")
            mpvHandle.setMpvRuntimeOption("cursor-autohide-fs-only", "no")
            DesktopRuntimeLog.info("MPV cursor autohide configured")
        }.onFailure {
            DesktopRuntimeLog.warn("MPV cursor autohide configuration failed message=${it.message}")
        }
    }

    private fun fail(error: DesktopPlayerError) {
        releaseDisplayWakeLock("fail-${error::class.simpleName}")
        stateFlow.value = stateFlow.value.copy(
            phase = DesktopPlayerPhase.Error,
            error = error,
            diagnostics = error.technicalMessage,
        )
    }

    private fun canReceiveCommands(): Boolean =
        !stopped && !nativeClosed && player.getCurrentPlaybackState() != PlaybackState.FINISHED

    private fun durationMs(): Long? =
        player.mediaProperties.value?.durationMillis?.takeIf { it > 0L }

    private fun snapshotForLog(): String =
        "state=${player.getCurrentPlaybackState()} posMs=${player.currentPositionMillis.value} durationMs=${durationMs() ?: -1}"

    private fun resetFreezeWatchdog() {
        watchdogLastPositionMs = -1L
        watchdogLastChangeAtMs = System.currentTimeMillis()
    }

    private fun resetExternalSubtitleState(reason: String) {
        if (nativeClosed) return
        externalSubtitleRequestCounter.incrementAndGet()
        externalSubtitleActive = false
        clearExternalSubtitleTempFiles(reason)
        runCatching {
            mpvHandle.setMpvRuntimeOption("sub-codepage", EmbeddedSubtitleCodepage)
            mpvHandle.setMpvRuntimeOption("embeddedfonts", "yes")
            mpvHandle.setMpvRuntimeOption("sub-ass-override", EmbeddedSubtitleAssOverride)
        }.onFailure { DesktopRuntimeLog.warn("MPV reset external subtitle state failed reason=$reason message=${it.message}") }
    }

    private inner class MpvController : PlayerEngineController {
        private val playLock = java.util.concurrent.atomic.AtomicBoolean(false)

        fun release() = releaseSoft()

        override fun play() {
            if (stopped) {
                val req = currentRequest
                if (req != null) {
                    DesktopRuntimeLog.info("MPV controller play after stop: reloading media source=${req.sourceUrl.redactedMediaUrl()}")
                    stopped = false
                    val before = snapshotForLog()
                    scope.launch {
                        val result = runCatching {
                            stateFlow.value = stateFlow.value.copy(phase = DesktopPlayerPhase.Preparing, error = null)
                            applyHlsSpecificOptionsIfNeeded(req.sourceUrl)
                            val headers = req.sourceHeaders.toMutableMap()
                            player.setMediaData(UriMediaData(req.sourceUrl, headers))
                            if (lastKnownPositionMs > 0L) {
                                pendingSeekMs = lastKnownPositionMs
                            }
                            player.resume()
                            mpvHandle.setPropertyBoolean("pause", false)
                        }
                        DesktopRuntimeLog.info("MPV controller play after stop before=$before result=${result.getOrNull()} after=${snapshotForLog()}")
                        result.onFailure {
                            DesktopRuntimeLog.error("MPV controller play after stop failed", it)
                            fail(DesktopPlayerError.MediaLoadFailed(backendName, "MPV reload after stop failed", it))
                        }
                    }
                    resetFreezeWatchdog()
                    lastPauseAtMs = 0L
                }
                return
            }
            if (!canReceiveCommands()) return
            if (!playLock.compareAndSet(false, true)) {
                DesktopRuntimeLog.info("MPV play: already in progress, skipping re-entrant call")
                return
            }
            try {
                val before = snapshotForLog()
                val result = runCatching {
                    player.resume()
                    mpvHandle.setPropertyBoolean("pause", false)
                }
                DesktopRuntimeLog.info("MPV controller play before=$before result=${result.getOrNull()} after=${snapshotForLog()}")
                result.onFailure { DesktopRuntimeLog.error("MPV controller play failed", it) }
                if (lastKnownPositionMs > 0L && lastPauseAtMs > 0L) {
                    val req = currentRequest
                    if (req != null && (req.sourceUrl.startsWith("http") || req.sourceUrl.startsWith("https"))) {
                        val pausedFor = System.currentTimeMillis() - lastPauseAtMs
                        if (pausedFor >= reconnectStaleMs) {
                            DesktopRuntimeLog.info("MPV play after pause: forcing stream reconnect via seek to ${lastKnownPositionMs}ms (pausedFor=${pausedFor}ms)")
                            mpvHandle.command("set", "pause", "no")
                            runCatching {
                                mpvHandle.command("seek", (lastKnownPositionMs / 1000.0).toString(), "absolute+exact")
                                player.currentPositionMillis.value = lastKnownPositionMs
                            }
                            mpvHandle.command("set", "pause", "no")
                            resetFreezeWatchdog()
                        } else {
                            DesktopRuntimeLog.info("MPV play after brief pause: skipping reconnect seek (pausedFor=${pausedFor}ms < ${reconnectStaleMs}ms)")
                        }
                    }
                }
                lastPauseAtMs = 0L
            } finally {
                playLock.set(false)
            }
        }

        override fun pause() {
            if (!canReceiveCommands()) return
            lastPauseAtMs = System.currentTimeMillis()
            val before = snapshotForLog()
            val result = runCatching { player.pause() }
            DesktopRuntimeLog.info("MPV controller pause before=$before result=${result.getOrNull()} after=${snapshotForLog()}")
            result.onFailure { DesktopRuntimeLog.error("MPV controller pause failed", it) }
        }

        override fun seekTo(positionMs: Long) {
            if (!canReceiveCommands()) return
            val durationMs = durationMs()
            val targetMs = positionMs.coerceAtLeast(0L).let { target -> durationMs?.let(target::coerceAtMost) ?: target }
            val before = snapshotForLog()
            val result = runCatching { mpvHandle.command("seek", (targetMs / 1000.0).toString(), "absolute+exact") }
            if (result.getOrNull() == true) player.currentPositionMillis.value = targetMs
            DesktopRuntimeLog.info(
                "MPV controller seekTo targetMs=$targetMs durationMs=${durationMs ?: -1} " +
                    "before=$before result=${result.getOrNull()} after=${snapshotForLog()}",
            )
            result.onFailure { DesktopRuntimeLog.error("MPV controller seekTo failed targetMs=$targetMs", it) }
        }

        override fun seekBy(offsetMs: Long) {
            if (!canReceiveCommands()) return
            seekTo(player.currentPositionMillis.value.coerceAtLeast(0L) + offsetMs)
        }

        override fun retry() = play()

        override fun configureIosVideoOutput(settings: PlayerSettingsUiState) {
            if (!canReceiveCommands()) return
            storeDesktopVideoTuningFromPlayerSettings(settings)
            applyDecoderSettings()
        }

        override fun setPlaybackSpeed(speed: Float) {
            if (!canReceiveCommands()) return
            player.features[PlaybackSpeed]?.set(speed.coerceIn(0.25f, 4.0f))
        }

        override fun currentVolume(): PlayerAudioLevel? {
            if (!canReceiveCommands()) return null
            val volume = mpvHandle.getMpvStringProperty("volume")
                .toDoubleOrNull()
                ?.div(100.0)
                ?.toFloat()
                ?.coerceIn(0f, 1f)
                ?: return null
            val muted = mpvHandle.getMpvBooleanProperty("mute")
            return PlayerAudioLevel(
                fraction = volume,
                isMuted = muted || volume <= 0.001f,
            )
        }

        override fun setVolume(level: Float): PlayerAudioLevel? {
            if (!canReceiveCommands()) return null
            val target = level.coerceIn(0f, 1f)
            runCatching {
                mpvHandle.setMpvProperty("volume", (target * 100.0).coerceIn(0.0, 100.0))
                mpvHandle.setMpvProperty("mute", target <= 0.001f)
            }.onFailure {
                DesktopRuntimeLog.error("MPV controller setVolume failed target=$target", it)
            }
            return currentVolume()
        }

        override fun getAudioTracks(): List<AudioTrack> =
            if (canReceiveCommands()) runCatching { mpvHandle.audioTracks() }.getOrDefault(emptyList()) else emptyList()

        override fun getSubtitleTracks(): List<SubtitleTrack> =
            if (canReceiveCommands()) runCatching { mpvHandle.subtitleTracks() }.getOrDefault(emptyList()) else emptyList()

        override fun selectAudioTrack(index: Int) {
            if (!canReceiveCommands()) return
            val tracks = getAudioTracks()
            if (index in tracks.indices) {
                runCatching { mpvHandle.setMpvProperty("aid", tracks[index].id) }
                    .onFailure { DesktopRuntimeLog.error("MPV selectAudioTrack failed index=$index", it) }
            }
        }

        override fun selectSubtitleTrack(index: Int) {
            if (!canReceiveCommands()) return
            if (index < 0) {
                runCatching {
                    externalSubtitleActive = false
                    mpvHandle.setMpvProperty("sid", "no")
                    applySubtitleStyleToCurrentTrack(latestSubtitleStyle, reason = "select-none")
                }
                return
            }
            val tracks = getSubtitleTracks()
            if (index in tracks.indices) {
                runCatching {
                    externalSubtitleActive = false
                    mpvHandle.setMpvProperty("sid", tracks[index].id)
                    applySubtitleStyleToCurrentTrack(latestSubtitleStyle, reason = "select-built-in")
                }
                    .onFailure { DesktopRuntimeLog.error("MPV selectSubtitleTrack failed index=$index", it) }
            }
        }

        override fun setSubtitleUri(url: String) {
            if (!canReceiveCommands()) return
            val requestId = externalSubtitleRequestCounter.incrementAndGet()
            removeExternalSubtitleTracks(cancelPendingRequest = false, reason = "replace-external")
            runCatching {
                externalSubtitleActive = true
                mpvHandle.setMpvRuntimeOption("sub-codepage", ExternalSubtitleCodepage)
                mpvHandle.setMpvRuntimeOption("sub-visibility", "yes")
                applySubtitleStyleToCurrentTrack(latestSubtitleStyle, reason = "set-external-preload")
            }
                .onFailure { DesktopRuntimeLog.error("MPV setSubtitleUri failed url=${url.redactedMediaUrl()}", it) }
            scope.launch(Dispatchers.IO) {
                val subtitleRef = runCatching { prepareExternalSubtitleReference(url) }
                    .onFailure {
                        DesktopRuntimeLog.warn(
                            "MPV external subtitle normalization failed url=${url.redactedMediaUrl()} message=${it.message}; using original URL",
                        )
                    }
                    .getOrDefault(url)
                if (requestId != externalSubtitleRequestCounter.get() || !canReceiveCommands()) {
                    DesktopRuntimeLog.info("MPV external subtitle add skipped stale request url=${url.redactedMediaUrl()}")
                    return@launch
                }
                runCatching {
                    mpvHandle.setMpvRuntimeOption("sub-codepage", ExternalSubtitleCodepage)
                    mpvHandle.setMpvRuntimeOption("sub-visibility", "yes")
                    mpvHandle.command("sub-add", subtitleRef, "select")
                    selectNewestExternalSubtitle()
                    applySubtitleStyleToCurrentTrack(latestSubtitleStyle, reason = "set-external")
                }.onFailure {
                    DesktopRuntimeLog.error("MPV setSubtitleUri failed url=${url.redactedMediaUrl()}", it)
                }
            }
        }

        override fun clearExternalSubtitle() {
            removeExternalSubtitleTracks(cancelPendingRequest = true, reason = "clear-external")
        }

        private fun removeExternalSubtitleTracks(cancelPendingRequest: Boolean, reason: String) {
            if (!canReceiveCommands()) return
            if (cancelPendingRequest) {
                externalSubtitleRequestCounter.incrementAndGet()
            }
            val handle = mpvHandle
            val hadExternalSubtitle = externalSubtitleActive
            val count = handle.getMpvIntProperty("track-list/count")
            if (count == null) {
                if (hadExternalSubtitle) {
                    externalSubtitleActive = false
                    applySubtitleStyleToCurrentTrack(latestSubtitleStyle, reason = "$reason-missing-track-list")
                }
                clearExternalSubtitleTempFiles("$reason-missing-track-list")
                return
            }
            for (i in count - 1 downTo 0) {
                val type = handle.getMpvStringProperty("track-list/$i/type")
                val external = handle.getMpvBooleanProperty("track-list/$i/external")
                if (type == "sub" && external) {
                    val id = handle.getMpvIntProperty("track-list/$i/id") ?: continue
                    runCatching { handle.command("sub-remove", id.toString()) }
                }
            }
            if (hadExternalSubtitle) {
                externalSubtitleActive = false
                applySubtitleStyleToCurrentTrack(latestSubtitleStyle, reason = reason)
            }
            clearExternalSubtitleTempFiles(reason)
        }

        private fun selectNewestExternalSubtitle() {
            val handle = mpvHandle
            val count = handle.getMpvIntProperty("track-list/count") ?: return
            var newestExternalSubtitleId: Int? = null
            for (i in 0 until count) {
                val type = handle.getMpvStringProperty("track-list/$i/type")
                val external = handle.getMpvBooleanProperty("track-list/$i/external")
                if (type == "sub" && external) {
                    newestExternalSubtitleId = handle.getMpvIntProperty("track-list/$i/id") ?: newestExternalSubtitleId
                }
            }
            newestExternalSubtitleId?.let { id ->
                handle.setMpvProperty("sid", id.toString())
            }
        }

        override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
            clearExternalSubtitle()
            selectSubtitleTrack(trackIndex)
        }

        override fun applySubtitleStyle(style: SubtitleStyleState) {
            latestSubtitleStyle = style
            if (!canReceiveCommands()) return
            applySubtitleStyleToCurrentTrack(style, reason = "settings")
        }

        private fun applySubtitleStyleToCurrentTrack(style: SubtitleStyleState, reason: String) {
            if (!canReceiveCommands()) return
            val handle = mpvHandle
            val colorHex = style.textColor.toMpvColorString()
            val outline = if (style.outlineEnabled) 2.0 else 0.0
            val subPos = 100 - style.bottomOffset
            runCatching {
                val selectedTrack = handle.selectedSubtitleTrackDetails()
                val useExternalSubtitleStyle = externalSubtitleActive || selectedTrack?.external == true
                val assOverrideMode = if (useExternalSubtitleStyle) ExternalSubtitleAssOverride else EmbeddedSubtitleAssOverride
                val codepage = if (useExternalSubtitleStyle) ExternalSubtitleCodepage else EmbeddedSubtitleCodepage

                val subDelaySecs = style.subtitleDelayMs / 1000.0

                handle.setMpvRuntimeOption("sub-codepage", codepage)
                handle.setMpvRuntimeOption("embeddedfonts", "yes")
                handle.setMpvRuntimeOption("sub-ass-override", assOverrideMode)
                handle.setMpvRuntimeOption("sub-color", colorHex)
                handle.setMpvRuntimeOption("sub-border-size", outline)
                handle.setMpvRuntimeOption("sub-font-size", style.fontSizeSp.toDouble())
                handle.setMpvRuntimeOption("sub-pos", subPos)
                handle.setMpvRuntimeOption("sub-align-y", "bottom")
                handle.setMpvRuntimeOption("sub-delay", subDelaySecs)

                DesktopRuntimeLog.info(
                    "MPV applySubtitleStyle selected=${selectedTrack?.toLogString() ?: "none"} " +
                        "reason=$reason assOverride=$assOverrideMode codepage=$codepage " +
                        "embeddedfonts=yes externalActive=$externalSubtitleActive " +
                        "appStyleTarget=${if (useExternalSubtitleStyle) "external-subtitle" else "embedded-plain-text"} " +
                        "subDelay=${style.subtitleDelayMs}ms",
                )
            }.onFailure { DesktopRuntimeLog.error("MPV applySubtitleStyle failed", it) }
        }

        fun switchSource(url: String, audioUrl: String?, headersJson: String?) {
            if (!canReceiveCommands()) return
            val previous = currentRequest ?: return
            val headers = parseHeadersJson(headersJson).ifEmpty { previous.sourceHeaders }
            DesktopRuntimeLog.info(
                "MPV switchSource reloadInPlace url=${url.redactedMediaUrl()} " +
                    "audio=${audioUrl?.redactedMediaUrl() ?: "none"} headersPresent=${headers.isNotEmpty()}",
            )
            scope.launch {
                load(
                    previous.copy(
                        sourceUrl = url,
                        sourceAudioUrl = audioUrl,
                        sourceHeaders = headers,
                        playWhenReady = true,
                    ),
                )
            }
        }
    }

    private fun prepareExternalSubtitleReference(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val scheme = uri.scheme?.lowercase() ?: return url
        if (scheme != "http" && scheme != "https") return url
        val request = HttpRequest.newBuilder(uri)
            .GET()
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "NuvioDesktop/1.0")
            .build()
        val response = subtitleHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            error("HTTP ${response.statusCode()}")
        }
        val text = response.body().decodeExternalSubtitleText()
        val extension = subtitleExtension(uri, text)
        val file = Files.createTempFile("nuvio-external-subtitle-", extension)
        Files.write(file, text.toByteArray(StandardCharsets.UTF_8))
        synchronized(externalSubtitleTempFiles) {
            externalSubtitleTempFiles.add(file)
        }
        DesktopRuntimeLog.info(
            "MPV external subtitle normalized url=${url.redactedMediaUrl()} temp=${file.toSafeLogPath()} extension=$extension",
        )
        return file.toUri().toString()
    }

    private fun ByteArray.decodeExternalSubtitleText(): String {
        val bytes = dropUtf8Bom()
        val decoded = runCatching { bytes.decodeStrictUtf8() }.getOrElse {
            String(bytes, Charset.forName("windows-1252"))
        }
        return decoded.repairCommonMojibake()
    }

    private fun ByteArray.dropUtf8Bom(): ByteArray =
        if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()) {
            copyOfRange(3, size)
        } else {
            this
        }

    private fun ByteArray.decodeStrictUtf8(): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(this))
            .toString()

    private fun String.repairCommonMojibake(): String {
        if ('Ã' !in this && 'Â' !in this && '�' !in this) return this
        if (any { it.code > 255 }) return this
        val repaired = runCatching {
            String(toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
        }.getOrNull() ?: return this
        return if (repaired.mojibakeScore() < mojibakeScore()) repaired else this
    }

    private fun String.mojibakeScore(): Int =
        count { it == 'Ã' || it == 'Â' || it == '�' }

    private fun subtitleExtension(uri: URI, text: String): String {
        val pathExtension = uri.path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when {
            pathExtension in setOf("ass", "ssa", "srt", "vtt") -> ".$pathExtension"
            text.trimStart().startsWith("[Script Info]", ignoreCase = true) -> ".ass"
            text.trimStart().startsWith("WEBVTT", ignoreCase = true) -> ".vtt"
            else -> ".srt"
        }
    }

    private fun clearExternalSubtitleTempFiles(reason: String) {
        val files = synchronized(externalSubtitleTempFiles) {
            externalSubtitleTempFiles.toList().also { externalSubtitleTempFiles.clear() }
        }
        files.forEach { file ->
            runCatching { Files.deleteIfExists(file) }
                .onFailure {
                    DesktopRuntimeLog.warn(
                        "MPV external subtitle temp cleanup failed reason=$reason file=${file.toSafeLogPath()} message=${it.message}",
                    )
                }
        }
    }

    private suspend fun resolveMediaUrl(url: String): String {
        if (!url.startsWith("http")) return url
        val originalUri = runCatching { URI(url) }.getOrNull() ?: return url
        System.err.println("[mediamp-debug] resolveMediaUrl ENTER url=${url.redactedMediaUrl()}")
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = HttpRequest.newBuilder(originalUri)
                    .method("GET", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.discarding())
                val finalUri = response.uri()
                System.err.println("[mediamp-debug] resolveMediaUrl statusCode=${response.statusCode()} finalUri=${finalUri.toString().redactedMediaUrl()}")
                if (finalUri != originalUri) {
                    DesktopRuntimeLog.info("MPV URL pre-resolved: redirect ${url.redactedMediaUrl()} -> ${finalUri.toString().redactedMediaUrl()}")
                }
                finalUri.toString()
            }.onFailure {
                DesktopRuntimeLog.warn("MPV URL pre-resolution failed url=${url.redactedMediaUrl()} error=${it.message}")
                System.err.println("[mediamp-debug] resolveMediaUrl FAILED url=${url.redactedMediaUrl()} error=${it.message}")
            }.getOrDefault(url)
        }
    }

    private fun MPVHandle.setMpvRuntimeOption(name: String, value: Any): Boolean {
        val stringValue = value.toString()
        return command("set", name, stringValue) || option(name, stringValue) || setMpvProperty(name, value)
    }

    private fun Path.toSafeLogPath(): String =
        runCatching { toAbsolutePath().toString() }.getOrDefault(toString())

    private data class MpvSubtitleTrackDetails(
        val id: Int,
        val codec: String,
        val external: Boolean,
        val title: String,
        val language: String,
    ) {
        fun toLogString(): String =
            "id=$id codec=${codec.ifBlank { "unknown" }} external=$external " +
                "title=${title.ifBlank { "none" }} lang=${language.ifBlank { "none" }}"
    }

    private fun org.openani.mediamp.mpv.MPVHandle.selectedSubtitleTrackDetails(): MpvSubtitleTrackDetails? {
        val count = getMpvIntProperty("track-list/count") ?: return null
        for (i in 0 until count) {
            if (getMpvStringProperty("track-list/$i/type") != "sub") continue
            if (!getMpvBooleanProperty("track-list/$i/selected")) continue
            val id = getMpvIntProperty("track-list/$i/id") ?: continue
            return MpvSubtitleTrackDetails(
                id = id,
                codec = getMpvStringProperty("track-list/$i/codec"),
                external = getMpvBooleanProperty("track-list/$i/external"),
                title = getMpvStringProperty("track-list/$i/title"),
                language = getMpvStringProperty("track-list/$i/lang"),
            )
        }
        return null
    }

    companion object {
        fun create(runtime: MpvRuntimeResolution): Result<MpvDesktopPlayerBackend> =
            runCatching {
                val configPath = null
                MpvDesktopPlayerBackend(
                    runtime = runtime,
                    player = MpvMediampPlayer(Unit, EmptyCoroutineContext, configPath),
                )
            }
    }
}

private fun parseHeadersJson(headersJson: String?): Map<String, String> {
    if (headersJson.isNullOrBlank()) return emptyMap()
    return runCatching {
        Json.parseToJsonElement(headersJson).jsonObject.mapNotNull { (key, value) ->
            val primitive = value as? JsonPrimitive ?: return@mapNotNull null
            val content = primitive.jsonPrimitive.content.trim()
            if (key.isBlank() || content.isBlank()) null else key.trim() to content
        }.toMap()
    }.getOrDefault(emptyMap())
}

private fun Color.toMpvColorString(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return "#${r.hex()}${g.hex()}${b.hex()}${a.hex()}"
}

private fun Int.hex(): String = toString(16).padStart(2, '0').uppercase()
