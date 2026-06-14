/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.source

import kotlinx.io.IOException
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.io.SeekableInput
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A playable media data.
 *
 * @see SeekableInputMediaData
 * @see UriMediaData
 */
public sealed interface MediaData {
    /**
     * Extra files associated with this media data.
     */
    public val extraFiles: MediaExtraFiles

    /**
     * Raw options to pass to the player.
     *
     * Note that this is implementation-specific. Some implementations may ignore this.
     */
    @ExperimentalMediampApi
    public val options: List<String>

    /**
     * Closes any the underlying resources held by this media data, if any.
     * This method should be idempotent. Calling it multiple times should have no effect.
     *
     * Note that this method might be called on the main thread.
     */
    public fun close()
}

/**
 * A [MediaData] that represents a media that may open a custom [SeekableInput] to provide the payload for playing.
 */
@SubclassOptInRequired(ExperimentalMediampApi::class)
public interface SeekableInputMediaData : MediaData {
    /**
     * The unique identifier of the media data.
     */
    public val uri: String

    /**
     * Returns the length of the video file in bytes, or `null` if not known.
     */
    @Throws(IOException::class)
    public fun fileLength(): Long?

    /**
     * Opens a new input stream to the video file.
     * The returned [SeekableInput] needs to be closed if it is no longer needed.
     *
     * The returned [SeekableInput] must be closed before a new [createInput] can be made.
     * Otherwise, it is undefined behavior.
     */
    public suspend fun createInput(coroutineContext: CoroutineContext = EmptyCoroutineContext): SeekableInput
}
