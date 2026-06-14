/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.metadata

import kotlin.jvm.JvmField
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Class containing metadata properties of a media, for example, title and total duration.
 *
 * Can be obtained from [org.openani.mediamp.MediampPlayer.mediaProperties].
 */
public class MediaProperties(
    /**
     * Title of the media (video).
     *
     * This value might be `null` if the title is unknown.
     */
    public val title: String? = null,
    /**
     * Total duration of the media in milliseconds.
     *
     * This value might be `-1` if the duration is unknown.
     */
    public val durationMillis: Long = -1L
) {
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaProperties) return false

        if (title != other.title) return false
        if (durationMillis != other.durationMillis) return false

        return true
    }

    public override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + durationMillis.hashCode()
        return result
    }

    /**
     * Creates a copy of this [MediaProperties] instance with the specified properties.
     */
    public fun copy(
        title: String? = this.title,
        durationMillis: Long = this.durationMillis,
    ): MediaProperties = MediaProperties(
        title = title,
        durationMillis = durationMillis,
    )

    public companion object {
        /**
         * Empty [MediaProperties] instance, with all properties set to their default values.
         */
        @JvmField
        public val Empty: MediaProperties = MediaProperties()
    }
}

/**
 * Total duration of the media, in Kotlin [Duration].
 */
public inline val MediaProperties.duration: Duration get() = durationMillis.milliseconds

/**
 * Returns an empty [MediaProperties] instance if [this] is `null`, or the original instance otherwise.
 */
public fun MediaProperties?.orEmpty(): MediaProperties = this ?: MediaProperties.Empty
