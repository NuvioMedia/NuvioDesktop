/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.features

import kotlinx.coroutines.flow.Flow
import org.openani.mediamp.InternalForInheritanceMediampApi

@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface PlaybackSpeed : Feature {
    /**
     * A cold flow of the current playback speed. `1.0` by default.
     *
     * `1.0` is the original speed, `2.0` is double speed, `0.5` is half speed, etc.
     */
    public val valueFlow: Flow<Float>

    /**
     * The current playback speed.
     */
    public val value: Float

    /**
     * Sets the playback speed to [speed].
     *
     * Playback speed settings will continue to be in effect even if the video has been switched.
     */
    public fun set(speed: Float)

    public companion object Key : FeatureKey<PlaybackSpeed>
}
