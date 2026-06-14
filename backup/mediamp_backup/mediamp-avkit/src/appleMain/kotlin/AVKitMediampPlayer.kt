/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.avkit

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.openani.mediamp.AbstractMediampPlayer
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.features.buildPlayerFeatures
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.isMuted
import platform.AVFoundation.pause
import platform.AVFoundation.playImmediatelyAtRate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setMuted
import platform.AVFoundation.setRate
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.Foundation.NSKeyValueChangeNewKey
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSKeyValueObservingProtocol
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL.Companion.URLWithString
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.darwin.NSObject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

@OptIn(InternalMediampApi::class, InternalForInheritanceMediampApi::class, ExperimentalForeignApi::class)
public class AVKitMediampPlayer(
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : AbstractMediampPlayer<AVKitMediampPlayer.AVKitData>(Dispatchers.Main) {
    override val impl: AVPlayer = AVPlayer()

    // ------------------------------------------------------------------------------------
    // State & Flows
    // ------------------------------------------------------------------------------------
    override val mediaProperties: MutableStateFlow<MediaProperties?> = MutableStateFlow(null)

    override val currentPositionMillis: MutableStateFlow<Long> = MutableStateFlow(0L)

    private var hasPlaybackStarted: Boolean = false

    @OptIn(ExperimentalMediampApi::class)
    private val bufferingFeature = object : Buffering {
        override val isBuffering = MutableStateFlow(false)
        override val bufferedPercentage = MutableStateFlow(0)
    }

    /**
     * Implement AudioLevelController feature by bridging to [AVPlayer.volume] and [AVPlayer.isMuted].
     */
    @OptIn(ExperimentalMediampApi::class)
    private val audioLevelController = AVKitAudioLevelController(impl)

    @OptIn(ExperimentalMediampApi::class)
    private val playbackSpeedFeature = AVKitPlaybackSpeed(impl)

    @OptIn(ExperimentalMediampApi::class)
    private val videoAspectRatioFeature = AVKitVideoAspectRatio()

    @OptIn(ExperimentalMediampApi::class)
    override val features: PlayerFeatures = buildPlayerFeatures {
        add(Buffering, bufferingFeature)
        add(AudioLevelController, audioLevelController)
        add(PlaybackSpeed, playbackSpeedFeature)
        add(VideoAspectRatio, videoAspectRatioFeature)
    }

    // ------------------------------------------------------------------------------------
    // Internal Setup
    // ------------------------------------------------------------------------------------

    private val coroutineScope: CoroutineScope =
        CoroutineScope(Dispatchers.Main + SupervisorJob(parentCoroutineContext[Job]))

    // KVO Observations
    private var timeControlStatusObserver: NSObject? = null
    private var lastPlayerItem: AVPlayerItem? = null
    private var playerItemStatusObserver: NSObject? = null

    private val notificationCenter = NSNotificationCenter.defaultCenter
    private var didPlayToEndObserver: Any? = null

    public inner class AVKitData(
        override val mediaData: MediaData,
        public val playerItem: AVPlayerItem,
    ) : Data(mediaData) {
        override fun release() {
            removePlayerItemObservers()
            impl.replaceCurrentItemWithPlayerItem(null)
            super.release()
            mediaProperties.value = null
            currentPositionMillis.value = 0L
            bufferingFeature.isBuffering.value = false
            bufferingFeature.bufferedPercentage.value = 0
            hasPlaybackStarted = false
        }
    }

    init {
        // Periodically update current position & buffering progress
        coroutineScope.launch {
            while (currentCoroutineContext().isActive) {
                currentPositionMillis.value = getCurrentPositionMillis()
                // We do not have an official property for “percentage buffered” from AVPlayer,
                // so here we leave the Buffering feature’s .bufferedPercentage at 0 or 100.
                // You can approximate it using 'loadedTimeRanges' from the AVPlayerItem if you wish.
                delay(200.milliseconds)
            }
        }
        // Observe changes in timeControlStatus -> map to PLAYING, PAUSED, or BUFFERING.
        timeControlStatusObserver = impl.observeValue(OBSERVATION_KEY_TIME_CONTROL_STATUS) {
            updatePlaybackStateFromTimeControlStatus(impl)
        }
    }

    // ------------------------------------------------------------------------------------
    // setMediaData
    // ------------------------------------------------------------------------------------
    @OptIn(ExperimentalMediampApi::class)
    override suspend fun setMediaDataImpl(data: MediaData): AVKitData {
        removePlayerItemObservers()
        hasPlaybackStarted = false
        bufferingFeature.isBuffering.value = false
        bufferingFeature.bufferedPercentage.value = 0
        currentPositionMillis.value = 0L
        mediaProperties.value = null

        val playerItem = when (data) {
            is UriMediaData -> makePlayerItemFromUriData(data)
            is SeekableInputMediaData -> makePlayerItemFromSeekableData(data)
        }

        lastPlayerItem = playerItem
        impl.replaceCurrentItemWithPlayerItem(playerItem)

        awaitPlayerItemReady(playerItem)

        didPlayToEndObserver = notificationCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = playerItem,
            queue = null,
        ) { _ ->
            playbackState.value = PlaybackState.FINISHED
            bufferingFeature.isBuffering.value = false
        }

        return AVKitData(data, playerItem)
    }

    // ------------------------------------------------------------------------------------
    // Playback control
    // ------------------------------------------------------------------------------------
    override fun resumeImpl() {
        hasPlaybackStarted = true
        impl.playImmediatelyAtRate(playbackSpeedFeature.value)
    }

    override fun pauseImpl() {
        impl.pause()
    }

    override fun stopPlaybackImpl() {
        hasPlaybackStarted = false
        bufferingFeature.isBuffering.value = false
        bufferingFeature.bufferedPercentage.value = 0
        playbackState.value = PlaybackState.FINISHED
        currentPositionMillis.value = 0L
        impl.pause()
        impl.replaceCurrentItemWithPlayerItem(null)
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun seekTo(positionMillis: Long) {
        val st = playbackState.value
        if (st < PlaybackState.READY) {
            // doc says ignore if < READY
            return
        }
        val item = impl.currentItem ?: return
        // Convert from ms to CMTime
        val clamped = positionMillis.coerceAtLeast(0L)
        val timeSec = clamped.toDouble() / 1000.0
        val cmTime = platform.CoreMedia.CMTimeMakeWithSeconds(timeSec, preferredTimescale = 600)
        item.seekToTime(cmTime)
        currentPositionMillis.value = clamped
    }

    // skip(deltaMillis) has default implementation in the interface => calls seekTo.

    // ------------------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------------------
    override fun getCurrentPlaybackState(): PlaybackState = playbackState.value

    @OptIn(ExperimentalForeignApi::class)
    override fun getCurrentPositionMillis(): Long {
        val cmTime = impl.currentTime()
        val seconds = cmTime.useContents {
            value.toDouble() / timescale.toDouble()
        }
        return (seconds * 1000).toLong().coerceAtLeast(0)
    }

    override fun getCurrentMediaProperties(): MediaProperties? = mediaProperties.value

    // ------------------------------------------------------------------------------------
    // Closing
    // ------------------------------------------------------------------------------------
    override fun closeImpl() {
        playbackState.value = PlaybackState.DESTROYED
        removePlayerItemObservers()

        timeControlStatusObserver?.let {
            impl.removeObserver(it, OBSERVATION_KEY_TIME_CONTROL_STATUS)
        }
        timeControlStatusObserver = null

        // free the player
        impl.pause()
        // Cancel coroutines
        coroutineScope.cancel()
    }

    // ------------------------------------------------------------------------------------
    // Utility: Observing Player & PlayerItem
    // ------------------------------------------------------------------------------------
    private fun removePlayerItemObservers() {
        playerItemStatusObserver?.let {
            lastPlayerItem?.removeObserver(it, OBSERVATION_KEY_STATUS)
        }
        playerItemStatusObserver = null
        lastPlayerItem = null

        didPlayToEndObserver?.let {
            notificationCenter.removeObserver(it)
        }
        didPlayToEndObserver = null
    }

    /**
     * Map AVPlayer.timeControlStatus => [PlaybackState].
     * We rely on the doc approach:
     *  - .Playing => PLAYING
     *  - .Paused => PAUSED (assuming we were at least READY)
     *  - .WaitingToPlayAtSpecifiedRate => BUFFERING
     */
    @OptIn(ExperimentalMediampApi::class)
    private fun updatePlaybackStateFromTimeControlStatus(player: AVPlayer) {
        val currentState = playbackState.value
        if (currentState <= PlaybackState.FINISHED) {
            return
        }
        when (player.timeControlStatus) {
            // 2
            AVPlayerTimeControlStatusPlaying -> {
                playbackState.value = PlaybackState.PLAYING
                bufferingFeature.isBuffering.value = false
                hasPlaybackStarted = true
            }

            // 0
            AVPlayerTimeControlStatusPaused -> {
                // Only transition to PAUSED after actual playback started.
                if (currentState == PlaybackState.PLAYING || currentState == PlaybackState.PAUSED_BUFFERING) {
                    playbackState.value = PlaybackState.PAUSED
                }
                bufferingFeature.isBuffering.value = false
            }

            // 1
            AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> {
                // iOS is waiting => buffering
                if (currentState == PlaybackState.PLAYING ||
                    (currentState == PlaybackState.READY && hasPlaybackStarted)
                ) {
                    playbackState.value = PlaybackState.PAUSED_BUFFERING
                    bufferingFeature.isBuffering.value = true
                }
            }

            else -> {
                // Shouldn't happen
            }
        }
    }

    /**
     * Read basic metadata from AVPlayerItem once it's [AVPlayerItemStatusReadyToPlay].
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun updateMediaProperties(playerItem: AVPlayerItem) {
        val durationSec = playerItem.duration.useContents {
            value.toDouble() / timescale.toDouble()
        }
        val ms = (durationSec * 1000).toLong().coerceAtLeast(0)
        mediaProperties.value = MediaProperties(
            title = null, // Could parse from metadata if you wish
            durationMillis = ms,
        )
    }

    private suspend fun awaitPlayerItemReady(playerItem: AVPlayerItem) {
        suspendCancellableCoroutine { cont ->
            var awaiting = true

            fun resumeOnce() {
                if (!awaiting || !cont.isActive) return
                awaiting = false
                cont.resume(Unit)
            }

            fun resumeWithFailure(ex: Throwable) {
                if (!awaiting || !cont.isActive) return
                awaiting = false
                cont.resumeWithException(ex)
            }

            fun handleStatus() {
                when (playerItem.status) {
                    AVPlayerItemStatusReadyToPlay -> {
                        updateMediaProperties(playerItem)
                        bufferingFeature.isBuffering.value = false
                        resumeOnce()
                    }

                    AVPlayerItemStatusFailed -> {
                        val error = IllegalStateException("AVPlayerItem failed to load media.")
                        if (awaiting) {
                            resumeWithFailure(error)
                        } else {
                            playbackState.value = PlaybackState.ERROR
                        }
                    }

                    else -> Unit
                }
            }

            val observer = playerItem.observeValue(OBSERVATION_KEY_STATUS) {
                handleStatus()
            }
            playerItemStatusObserver = observer
            cont.invokeOnCancellation {
                awaiting = false
                if (playerItemStatusObserver === observer) {
                    playerItem.removeObserver(observer, OBSERVATION_KEY_STATUS)
                    playerItemStatusObserver = null
                }
            }

            handleStatus()
        }
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------
    private fun makePlayerItemFromUriData(data: UriMediaData): AVPlayerItem {
        val asset = AVURLAsset(
            URLWithString(data.uri)!!,
            options = mapOf(
                "AVURLAssetHTTPHeaderFieldsKey" to data.headers.toMap(),
            ),
        )
        return AVPlayerItem(asset)
    }

    private fun makePlayerItemFromSeekableData(data: SeekableInputMediaData): AVPlayerItem {
        // On iOS, playing from arbitrary input is more complex than with ExoPlayer. 
        // You must set up a custom AVAssetResourceLoaderDelegate or use a local temp file approach.
        // For demonstration, we just throw NotImplementedError:
        throw UnsupportedOperationException("SeekableInputMediaData is not directly supported in AVKitMediampPlayer yet.")
    }

    private companion object {
        const val OBSERVATION_KEY_STATUS = "status"
        const val OBSERVATION_KEY_TIME_CONTROL_STATUS = "timeControlStatus"
    }
}

@OptIn(InternalForInheritanceMediampApi::class)
internal class AVKitAudioLevelController(
    private val player: AVPlayer,
) : AudioLevelController {
    private val _volume = MutableStateFlow(player.volume.coerceIn(0f, 1f))
    private val _isMute = MutableStateFlow(player.isMuted())

    override val volume: StateFlow<Float> get() = _volume
    override val maxVolume: Float = 1.0f
    override val isMute: StateFlow<Boolean> get() = _isMute

    override fun setMute(mute: Boolean) {
        player.setMuted(mute)
        _isMute.value = mute
    }

    override fun setVolume(volume: Float) {
        val coerced = volume.coerceIn(0f, maxVolume)
        player.volume = coerced
        // If we're unmuting by setting volume > 0, also ensure isMuted is false.
        if (coerced > 0f && player.isMuted()) {
            player.setMuted(false)
            _isMute.value = false
        }
        _volume.value = coerced
    }

    override fun volumeUp(value: Float) {
        setVolume(_volume.value + value)
    }

    override fun volumeDown(value: Float) {
        setVolume(_volume.value - value)
    }
}

@OptIn(InternalForInheritanceMediampApi::class)
internal class AVKitPlaybackSpeed(
    private val player: AVPlayer
) : PlaybackSpeed {
    private val _value = MutableStateFlow(
        1.0f, // AVPlayer impl.rate default is 0.0f, so we set 1.0f as default to align with other platforms.
    )

    override val valueFlow: Flow<Float> get() = _value
    override val value: Float get() = _value.value

    override fun set(speed: Float) {
        _value.value = speed
        player.setRate(speed)
    }
}

@OptIn(InternalForInheritanceMediampApi::class)
internal class AVKitVideoAspectRatio : VideoAspectRatio {
    private val _mode = MutableStateFlow(AspectRatioMode.FIT)

    override val mode: StateFlow<AspectRatioMode> get() = _mode

    override fun setMode(mode: AspectRatioMode) {
        _mode.value = mode
    }
}

/**
 * Simplified convenience for KVO on a K/N object. You could replace this with your
 * own safer approach, or a well-tested library that provides bridging.
 */
@ExperimentalForeignApi
private inline fun <T : NSObject> T.observeValue(
    keyPath: String,
    crossinline block: (Any) -> Unit
): NSObject {
    val observer = object : NSObject(), NSKeyValueObservingProtocol {

        override fun observeValueForKeyPath(
            keyPath: String?,
            ofObject: Any?,
            change: Map<Any?, *>?,
            context: COpaquePointer?
        ) {
            block(change!![NSKeyValueChangeNewKey]!!)
        }
    }
    this.addObserver(
        observer,
        forKeyPath = keyPath,
        options = NSKeyValueObservingOptionNew,
        context = null,
    )
    return observer
}
