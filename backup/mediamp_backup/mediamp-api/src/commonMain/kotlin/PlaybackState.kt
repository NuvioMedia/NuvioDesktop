/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp


/**
 * The current playback state of the player.
 *
 * ## State is comparable
 *
 * State is comparable in [ordinal] order. This is inspired by the Lifecycle design from Android.
 * For example, to check if playback is running:
 *
 * ```kotlin
 * fun PlaybackState.isPlaybackRunning() = this >= PlaybackState.PAUSED
 * ```
 *
 * See documentation of [MediampPlayer] to get te flowchart of state transformation and understand how state changes.
 * Also see each state in [PlaybackState] for more details.
 *
 * @see MediampPlayer
 * @see MediampPlayer.playbackState
 */
public enum class PlaybackState {
    /**
     * Player is destroyed and has recycled all resources.
     *
     * Any method will take no effect while in this state.
     * It is safe to drop the reference to the player and also the only thing you can do.
     */
    DESTROYED,

    /**
     * An error has occured, causing the player to stop playing.
     *
     * If the error is recoverable, the player will transform into [CREATED] state.
     * Otherwise the player will should be destroyed and turn to [DESTROYED] state.
     *
     * Note at it is decided by user whether the error is recoverable.
     * When error has occurred, user may call [MediampPlayer.setMediaData] to restart playback or [MediampPlayer.close] to release player.
     */
    ERROR,

    /**
     * Player is created but not yet loaded with any media data.
     *
     * This is the initial state of [MediampPlayer].
     * At this state, implementations should initialize and setup the player, typically initializes at `init {}` block.
     *
     * By [setting media][MediampPlayer.setMediaData], the player will transform into [READY] state.
     */
    CREATED,

    /**
     * Playback is finished.
     *
     * In this state, resources are still held by the player, meaning the player can play another media later.
     * State transitions to [READY] when a new media is set using [MediampPlayer.setMediaData].
     *
     * If the player is not going to play another media, call [MediampPlayer.close] to release resources.
     * Then the state will transform into [DESTROYED].
     */
    FINISHED,

    /**
     * Player has loaded a media data and will start playback as soon as the first frame is ready.
     */
    READY,

    /**
     * Playback is paused by user.
     */
    PAUSED,

    /**
     * Playback is playing.
     */
    PLAYING,

    /**
     * Playback is paused due to buffering.
     *
     * You can also pause the playback by [MediampPlayer.pause] so that playback will remain [PAUSED] state after buffer complete.
     */
    PAUSED_BUFFERING,
    ;
}

public val PlaybackState.isPlaying: Boolean
    get() = this == PlaybackState.PLAYING