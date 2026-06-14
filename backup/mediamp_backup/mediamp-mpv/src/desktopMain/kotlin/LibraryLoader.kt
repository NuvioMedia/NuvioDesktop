/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.openani.mediamp.internal.Platform
import org.openani.mediamp.internal.currentPlatform

object LibraryLoader {
    fun loadLibraries() {
        if (currentPlatform() is Platform.Android || currentPlatform() is Platform.Windows || currentPlatform() is Platform.Linux) {
            System.loadLibrary("mediampv")
        }
    }
}