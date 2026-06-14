/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.features

import kotlinx.coroutines.flow.StateFlow
import org.openani.mediamp.InternalForInheritanceMediampApi

/**
 * Video aspect ratio scaling modes.
 */
public enum class AspectRatioMode {
    /**
     * Fit the video to the container while maintaining the original aspect ratio.
     * The video will be scaled to fit entirely within the container, potentially leaving black bars.
     */
    FIT,

    /**
     * Stretch the video to fill the entire container.
     * The video aspect ratio may be changed to match the container.
     */
    STRETCH,

    /**
     * Fill the container while maintaining the original aspect ratio.
     * The video may be cropped if the aspect ratios don't match.
     */
    CROP
}

/**
 * An optional feature of the [org.openani.mediamp.MediampPlayer] that allows controlling video aspect ratio scaling.
 */
@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface VideoAspectRatio : Feature {
    /**
     * A hot flow of the current aspect ratio mode.
     */
    public val mode: StateFlow<AspectRatioMode>

    /**
     * Sets the aspect ratio mode.
     */
    public fun setMode(mode: AspectRatioMode)

    public companion object Key : FeatureKey<VideoAspectRatio>
}