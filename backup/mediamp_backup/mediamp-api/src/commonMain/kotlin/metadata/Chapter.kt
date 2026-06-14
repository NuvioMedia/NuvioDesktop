/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.metadata

import org.openani.mediamp.InternalMediampApi

/**
 * Class containing information about a chapter of a video.
 */
public class Chapter @InternalMediampApi constructor(
    /**
     * Name of the chapter.
     */
    public val name: String,
    /**
     * Duration of the chapter in milliseconds.
     */
    public val durationMillis: Long,
    /**
     * Absolute offset of the chapter from the beginning of the video in milliseconds.
     */
    public val offsetMillis: Long
)
