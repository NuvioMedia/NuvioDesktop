/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope

internal class FrameInterpolator {
    internal var updateSubscription by mutableLongStateOf(0L)
        private set

    internal suspend fun frameLoop() {
        coroutineScope {
            while (true) {
                withFrameNanos {
                    updateSubscription++
                }
            }
        }
    }
}