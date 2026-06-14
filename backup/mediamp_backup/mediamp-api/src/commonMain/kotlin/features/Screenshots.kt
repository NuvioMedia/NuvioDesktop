/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.features

/**
 * An optional feature of the [org.openani.mediamp.MediampPlayer] that allows taking screenshots of the current video frame.
 */
public interface Screenshots : Feature {
    /**
     * Take a screenshot of the current video frame and saves it to a file on the system filesystem.
     */
    public suspend fun takeScreenshot(destinationFile: String)

    public companion object Key : FeatureKey<Screenshots>
}
