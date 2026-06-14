/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openani.mediamp.source.MediaData
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Default abstract implementation of [MediampPlayer].
 * 
 * Method [setMediaData], [resume], [pause], [stopPlayback] and [close] are wrapped, 
 * please implement the actual playback control logic in corresponding `xxxImpl` methods. Note that:
 *
 * - These methods are called in UI thread, so implementations should not do any heavy work.
 * 
 * - These methods will only be called when the playback state is valid at its state transformation path, 
 * so it is not necessary to validate playback state.
 * 
 * - These methods (except [setMediaDataImpl]) should ensure that playback state must be transformed to target state in the future.
 * You may not call these methods at your player core state listener to avoid recursive loop.
 * [setMediaDataImpl] is special, new playback state will be set by this class after [setMediaDataImpl] returned.
 * 
 * - State transformation is allowed to be made immediately when the methods are called. 
 * For example, you may either change playbackState before the method returns, OR return the function and change state later in the background.
 * 
 * - No error occurred, error at main thread will crash the application.
 * 
 * - [setMediaDataImpl] can be called at any thread. 
 * If error occurred, playback state will be set to [PlaybackState.ERROR] and error will be rethrow.
 */
@InternalMediampApi
@OptIn(InternalForInheritanceMediampApi::class)
public abstract class AbstractMediampPlayer<D : AbstractMediampPlayer.Data>(
    private val defaultDispatcher: CoroutineContext = Dispatchers.Default,
) : MediampPlayer {
    override val playbackState: MutableStateFlow<PlaybackState> = MutableStateFlow(PlaybackState.CREATED)

    /**
     * Currently playing resource that should be closed when the controller is closed.
     * @see setMediaData
     */
    protected val openResource: MutableStateFlow<D?> = MutableStateFlow(null)

    public open class Data(
        public open val mediaData: MediaData,
    ) {
        /**
         * Release the media data resources.
         */
        public open fun release() {
            mediaData.close()
        }
    }

    final override val mediaData: Flow<MediaData?> = openResource.map { it?.mediaData }

    final override val playbackProgress: Flow<Float>
        get() = combine(mediaProperties.filterNotNull(), currentPositionMillis) { properties, duration ->
            if (properties.durationMillis == 0L) {
                return@combine 0f
            }
            (duration.toFloat() / properties.durationMillis.toFloat()).coerceIn(0f, 1f)
        }

    private val setVideoSourceMutex = Mutex()
    private val closed = MutableStateFlow(false)

    final override suspend fun setMediaData(data: MediaData): Unit = withContext(defaultDispatcher) {
        if (closed.value || playbackState.value == PlaybackState.DESTROYED) {
            return@withContext
        }
        setVideoSourceMutex.withLock {
            val currentState = playbackState.value
            if (closed.value || currentState == PlaybackState.DESTROYED) {
                return@withLock
            }

            // playback has set media data, stop previous first.
            if (currentState >= PlaybackState.READY) {
                val previousResource = openResource.value
                if (data == previousResource?.mediaData) {
                    return@withLock
                }
                // stop playback if running
                if (currentState >= PlaybackState.PAUSED) {
                    stopPlaybackImpl()
                }

                openResource.value = null
                previousResource?.release()
            }

            val opened = try {
                setMediaDataImpl(data)
            } catch (e: CancellationException) {
                playbackState.value = PlaybackState.ERROR
                throw e
            } catch (e: Exception) {
                playbackState.value = PlaybackState.ERROR
                throw e
            }

            // Player is closed before setMediaDataImpl is finished
            if (closed.value) {
                opened.release()
                return@withLock
            }

            openResource.value = opened
            playbackState.value = PlaybackState.READY
        }
    }

    /**
     * Resolves [data] for playback.
     * 
     * @see setMediaData
     */
    protected abstract suspend fun setMediaDataImpl(data: MediaData): D

    final override fun resume() {
        val currState = playbackState.value
        if (currState == PlaybackState.READY || currState == PlaybackState.PAUSED) {
            resumeImpl()
        }
    }

    /**
     * Playback state must change to [PlaybackState.PLAYING] in the future.
     * 
     * @see resume
     */
    protected abstract fun resumeImpl()

    final override fun pause() {
        if (playbackState.value > PlaybackState.PAUSED) {
            pauseImpl()
        }
    }

    /**
     * Playback state must change to [PlaybackState.PAUSED] in the future.
     * 
     * @see pause
     */
    protected abstract fun pauseImpl()

    final override fun stopPlayback() {
        if (playbackState.value < PlaybackState.FINISHED) return

        stopPlaybackImpl()
        releaseOpenedMediaData()
    }

    /**
     * Playback state must change to [PlaybackState.FINISHED] in the future.
     * 
     * @see stopPlayback
     */
    protected abstract fun stopPlaybackImpl()

    private fun releaseOpenedMediaData() {
        // TODO: 2024/12/16 proper synchronization?
        val value = openResource.value
        openResource.value = null
        value?.release()
    }

    public final override fun close() {
        if (closed.getAndUpdate { true }) return // already called, avoid multiple calls
        if (playbackState.value <= PlaybackState.DESTROYED) return // already closed

        releaseOpenedMediaData()
        closeImpl()
    }

    /**
     * Playback state must change to [PlaybackState.DESTROYED] in the future.
     * 
     * @see close
     */
    protected abstract fun closeImpl()
}