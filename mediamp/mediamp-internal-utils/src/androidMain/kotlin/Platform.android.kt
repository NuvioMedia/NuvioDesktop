/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.internal

import android.os.Build

internal actual fun currentPlatformImpl(): Platform {
    return Build.SUPPORTED_ABIS.getOrNull(0)?.let { abi ->
        when (abi.lowercase()) {
            "armeabi-v7a" -> Platform.Android(Arch.ARMV7A)
            "arm64-v8a" -> Platform.Android(Arch.ARMV8A)
            "x86_64" -> Platform.Android(Arch.X86_64)
            else -> Platform.Android(Arch.ARMV8A)
        }
    } ?: Platform.Android(Arch.ARMV8A)
}
