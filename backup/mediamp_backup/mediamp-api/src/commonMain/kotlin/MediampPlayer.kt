/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(InternalMediampApi::class)

package org.openani.mediamp

import androidx.annotation.UiThread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlayerFeatures
import org.openani.mediamp.metadata.MediaProperties
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData

/**
 * An extensible media player that plays [MediaData]s. Instances can be obtained from a [MediampPlayerFactory].
 *
 * The [MediampPlayer] interface itself defines only the minimal API for controlling the player, including:
 * - Playback State: [playbackState], [mediaData], [mediaProperties], [currentPositionMillis], [playbackProgress]
 * - Playback Control: [pause], [resume], [stopPlayback], [seekTo], [skip]
 *
 * Depending on whether the underlying player implementation supports a feature, [features] can be used to access them.
 *
 * ## Lifecycle
 * 
 * MediampPlayer uses [PlaybackState] to represent the current state of the player.
 * 
 * MediampPlayer implementations (with VLC or MPV, or any others) are required to respect to the following state transition mechanism.
 * 
 * > Hint: You may toggle off doc rendering in IDE if diagram glitches
 * 
 * ```
 * +-----------+  +-------+  +---------+  +----------+  +-------+  +--------+  +---------+  +-----------+
 * | DESTROYED |  | ERROR |  | CREATED |  | FINISHED |  | READY |  | PAUSED |  | PLAYING |  | BUFFERING |
 * +-----+-----+  +---+---+  +----+----+  +----+-----+  +---+---+  +---+----+  +----+----+  +-----+-----+
 *       |            |           |            |            |          |            |             |
 *       |            |-----------+------------+----------->*<---------+------------+-------------|  setMediaData
 *       |            |           |            |            |          |            |             |
 *       |            |           |            |            |----------+----------->|             |  resume
 *       |            |           |            |            |          |            |             |
 *       |            |           |            |            |          |            |------------>|  (buffering) 
 *       |            |           |            |            |          |            |             |  seekTo/skip
 *       |            |           |            |            |          |            |             |  
 *       |            |           |            |            |          |            |<------------|  (buffer complete)
 *       |            |           |            |            |          |            |             |
 *       |            |           |            |            |          |<-----------+-------------|  pause
 *       |            |           |            |            |          |            |             |
 *       |            |           |            |<-----------+----------+------------+-------------|  stopPlayback
 *       |            |           |            |            |          |            |             |  (or playback finished)
 *       |            |           |            |            |          |            |             |
 *       |            |<----------+------------+------------+----------+------------+-------------|  (error)
 *       |            |           |            |            |          |            |             |
 *       |<-----------+-----------+------------+------------+----------+------------+-------------|  close
 *       |            |           |            |            |          |            |             |
 * +-----+-----+  +---+---+  +----+----+  +----+-----+  +---+---+  +---+----+  +----+----+  +-----+-----+
 * | DESTROYED |  | ERROR |  | CREATED |  | FINISHED |  | READY |  | PAUSED |  | PLAYING |  | BUFFERING |
 * +-----------+  +-------+  +---------+  +----------+  +-------+  +--------+  +---------+  +-----------+
 * 
 * ```
 * Calling the method labelled to the right of the diagram, at any state included in the path, 
 * will transform the state to the destination state pointed by arrow.
 * 
 * For example, calling [stopPlayback] when `state >= READY`(incl [READY][PlaybackState.READY], [PAUSED][PlaybackState.PAUSED], 
 * [PLAYING][PlaybackState.PLAYING], [BUFFERING][PlaybackState.PAUSED_BUFFERING]) will always transform state to `FINISHED`.
 * 
 * ### Invalid calls are ignored
 *
 * Calls to any method while not at its state transformation path will be ignored.
 * 
 * For example, calling [stopPlayback] at state [FINISHED][PlaybackState.FINISHED], [CREATED][PlaybackState.CREATED], 
 * [ERROR][PlaybackState.ERROR] and [DESTROYED][PlaybackState.DESTROYED] will be ignored and take no effect.
 *
 * ### State transform directly to target state
 * 
 * Although each method has its transformation path, calling will not produce intermediate state.
 * 
 * For example, call [close] at [PLAYING][PlaybackState.PLAYING] will directly transform state to [DESTROYED][PlaybackState.DESTROYED].
 * Any state of `state >= ERROR && state <= PAUSED` will be emitted.
 * 
 * ### [setMediaData] is special
 *
 * `setMediaData` has special transformation path. It will always transform state into [READY][PlaybackState.READY] and may have intermediate state transformation.
 * Because user can set new media data at any state or any time except [DESTROYED][PlaybackState.DESTROYED] state, including [READY][PlaybackState.READY] state itself.
 * 
 * See [setMediaData] for more details.
 *
 * ### Error can occurred at any time
 * 
 * When *fatal error* occurred, state will always be transformed to [ERROR][PlaybackState.ERROR] directly.
 *
 * Error state has high priority. If an error occurred at background while calling a method, final state should be [ERROR][PlaybackState.ERROR].
 * 
 * ## Additional Features
 *
 * - [org.openani.mediamp.features.AudioLevelController]: Controls the audio volume and mute state.
 * - [org.openani.mediamp.features.Buffering]: Monitors the buffering progress.
 * - [org.openani.mediamp.features.PlaybackSpeed]: Controls the playback speed.
 * - [org.openani.mediamp.features.Screenshots]: Captures screenshots of the video.
 *
 * To obtain a feature, use the [PlayerFeatures.get] on [features].
 *
 * ## Threading Model
 *
 * This interface is not thread-safe. Concurrent calls to [resume] will lead to undefined behavior.
 * However, flows might be collected from multiple threads simultaneously while performing another call like [resume] on a single thread.
 *
 * Playback control methods are expected to be called from the UI thread. Calls from illegal threads will cause an exception.
 * Asynchronous operations of actual player implementations should ensure that playback state must be transformed to target state.
 *
 * On other platforms, calls are not required to be on the UI thread but should still be called from a single thread.
 * The implementation is guaranteed to be non-blocking and fast so, it is a recommended approach of making all calls from the UI thread in common code.
 *
 * ## Not safe for inheritance
 *
 * [MediampPlayer] interface is not safe for inheritance from third-party users, as new abstract methods might be added in the future.
 */
@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface MediampPlayer : AutoCloseable {
    /**
     * The underlying player implementation.
     * It can be cast to the actual player implementation to access additional features that are not yet ported by Mediamp.
     * 
     * *WARNING*: You should not access methods which may change playback state.
     * Otherwise the state of [MediampPlayer] will be inconsistent with the actual state of the player, which will cause unexpected behaviours.
     *
     * Refer to platform-specific inheritor of [MediampPlayer] for the actual type of this property.
     */
    public val impl: Any

    /**
     * A hot flow of the current playback state. Collect on this flow to receive state updates.
     *
     * States might be changed either by user interaction ([resume]) or by the player itself (e.g. decoder errors).
     *
     * To retrieve the current state without suspension, use [getCurrentPlaybackState].
     * 
     * `Implementation notes`: New state must be emitted only when the call to the delegated player finishes an transition. 
     * For example, emit `PlaybackState.PLAYING` only if `ExoPlayer.resume()` succeeds.
     *
     * @see getCurrentPlaybackState
     */
    public val playbackState: StateFlow<PlaybackState>

    /**
     * The video data of the currently playing video.
     */
    public val mediaData: Flow<MediaData?>

    /**
     * Properties of the video being played.
     *
     * Note that it may not be available immediately after [setMediaData] returns,
     * since the properties may be callback from the underlying player implementation.
     *
     * To get more metadata information, e.g. audio tracks, subtitles and chapters, use [features] to get [MediaMetadata].
     * @see features
     * @see MediaMetadata
     * @see getCurrentMediaProperties
     */
    public val mediaProperties: StateFlow<MediaProperties?>

    /**
     * Gets the current media properties without suspension.
     *
     * To subscribe for updates, use [mediaProperties].
     * @see mediaProperties
     */
    public fun getCurrentMediaProperties(): MediaProperties?

    /**
     * Current playback position of the video being played in millis seconds, ranged from `0` to [MediaProperties.durationMillis].
     *
     * `0` if no video is being played ([mediaData] is null).
     *
     * To obtain the current position without suspension, use [getCurrentPositionMillis].
     *
     * @see getCurrentPositionMillis
     */
    public val currentPositionMillis: StateFlow<Long>

    /**
     * A cold flow of the current playback progress, ranged from `0.0` to `1.0`.
     *
     * There is no guarantee on the frequency of updates, but it should normally be updated at once per second.
     */
    public val playbackProgress: Flow<Float>

    /**
     * Additional features that are supported by the underlying player implementation.
     */
    public val features: PlayerFeatures

    /**
     * Sets the media data to play, updating [mediaData], and calling the underlying player implementation to start playing.
     *
     * This method is thread-safe and can be called from any thread, including the UI thread.
     *
     * Setting the same [MediaData] will be ignored.
     *
     * ### State transition
     * 
     * If [data] is not equal to the currently playing media data, the later will be closed before [data] is set. 
     * That is equivalent to (atomically) calling [stopPlayback] before calling this method, in which case [playbackState] may emit a [FINISHED]. 
     *
     * **State transition may be asynchronous and cancellable.**
     * Depending on whether the player implements synchronous opening or asynchronous opening, 
     * this method returns normally, [playbackState] either has already emitted [READY][PlaybackState.READY] or will emit it in the near future. 
     * In other words:
     * 
     * - If the player implements synchronous media opening (e.g. [TestMediampPlayer][org.openani.mediamp.test.TestMediampPlayer]), observers of [playbackState] will have already seen an [READY][PlaybackState.READY] state before this method returns. 
     * Or, this method may throw an exception to indicate an error, and transit state to [ERROR][PlaybackState.ERROR].
     * 
     * - If the player implements asynchronous media opening (e.g. ExoPlayer), observers of [playbackState] MAY NOT have already seen an [READY][PlaybackState.READY] state before this method returns. 
     * Instead, the observers may collect an [READY][PlaybackState.READY] in arbitrary time after this method returns.
     * Decoding errors can only be seen by the observers, not the caller of [setMediaData]. 
     * If before the [READY][PlaybackState.READY] is emit (i.e. before initial video decoding is completed), [setMediaData] is called again, a new asynchronous opening process will start, cancelling the old one. 
     * So observers will not see an [READY][PlaybackState.READY] for the old media, but only the one for the new [data].
     * 
     * ### Error handling
     * 
     * This method will open media data by calling [MediaData.open], if and only if the [data] instance is different from the currently playing media data.
     * 
     * If an exception is occurred while opening, the playback state will transform to [ERROR][PlaybackState.ERROR], 
     * while the exception will also be propagated to the caller.
     * 
     * Note that only exceptions during [opening][MediaData.open] are propagated. 
     * Exceptions happened in the player implementation, for example, asynchronous video decoding, etc., will NOT be thrown from this method. 
     * These errors can be seen by observing the [playbackState] flow.
     *
     * @see stopPlayback
     */
    public suspend fun setMediaData(data: MediaData)

    /**
     * Gets the current playback state without suspension.
     *
     * To subscribe for updates, use [playbackState].
     *
     * @see playbackState
     */
    public fun getCurrentPlaybackState(): PlaybackState

    /**
     * Obtains the exact current playback position of the video in milliseconds, without suspension.
     *
     * If no video is being played, this method will return `0`.
     *
     * To subscribe for updates, use [currentPositionMillis].
     */
    public fun getCurrentPositionMillis(): Long

    /**
     * Resumes playback.
     *
     * If there is no video source set, this method will do nothing.
     * @see togglePause
     */
    @UiThread
    public fun resume()

    /**
     * Pauses playback.
     *
     * If there is no video source set, this method will do nothing.
     * @see togglePause
     */
    @UiThread
    public fun pause()

    /**
     * Stops playback, releasing all resources and setting [mediaData] to `null`.
     * Subsequent calls to [resume] will do nothing.
     *
     * [currentPositionMillis] will be reset to `0`.
     *
     * To play again, call [setMediaData].
     */
    @UiThread
    public fun stopPlayback()

    /**
     * Jumps playback to the specified position.
     *
     * // TODO argument errors?
     */
    @UiThread
    public fun seekTo(positionMillis: Long)

    /**
     * Skips the current playback position by [deltaMillis].
     * Positive [deltaMillis] will skip forward, and negative [deltaMillis] will skip backward.
     *
     * If the player is paused, it will remain paused, but it is guaranteed that the new frame will be displayed.
     * If there is no video source set, this method will do nothing.
     *
     * // TODO argument errors?
     */
    @UiThread
    public fun skip(deltaMillis: Long) {
        seekTo(getCurrentPositionMillis() + deltaMillis)
    }

    /**
     * Closes the player, releasing all resources held by the player.
     *
     * This operation is permanent.
     * After [close], calling any method from the player will either result in an exception or have no effect.
     * Flows will emit no value.
     *
     * This method must be called on the UI thread as some backends may require it.
     */
    public override fun close()
}

@Suppress("DeprecatedCallableAddReplaceWith", "UnusedReceiverParameter")
@Deprecated(
    message = "'stop' is ambiguous. " +
            "To stop current playback, use `MediampPlayer.stopPlayback()`. " +
            "To close the player and release any background resources, use `MediampPlayer.close()`.",
    level = DeprecationLevel.ERROR,
)
public fun MediampPlayer.stop(): Nothing = throw NotImplementedError("stop")

/**
 * Plays the video at the specified [uri], e.g. a local file or a remote URL.
 */
public suspend fun MediampPlayer.playUri(uri: String): Unit =
    setMediaData(UriMediaData(uri, emptyMap(), MediaExtraFiles()))

/**
 * Toggles between [MediampPlayer.pause] and [MediampPlayer.resume] based on the current playback state.
 */
public fun MediampPlayer.togglePause() {
    val currentState = getCurrentPlaybackState()
    if (currentState == PlaybackState.PLAYING) {
        pause()
    } else if (currentState == PlaybackState.PAUSED) {
        resume()
    }
}