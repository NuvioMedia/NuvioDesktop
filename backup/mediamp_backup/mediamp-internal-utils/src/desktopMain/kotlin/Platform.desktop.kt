/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.internal

internal actual fun currentPlatformImpl(): Platform {
    val os = System.getProperty("os.name")?.lowercase() ?: error("Cannot determine platform, 'os.name' is null.")
    val arch = getArch()
    return when {
        "mac" in os || "os x" in os || "darwin" in os -> Platform.MacOS(arch)
        "windows" in os -> Platform.Windows(arch)
        "linux" in os || "redhat" in os || "debian" in os || "ubuntu" in os -> Platform.Linux(arch)
        else -> throw UnsupportedOperationException("Unsupported platform: $os")
    }
}

private fun getArch() = System.getProperty("os.arch").lowercase().let {
    when {
        "x86" in it || "x64" in it || "amd64" in it -> Arch.X86_64
        "arm" in it || "aarch" in it -> Arch.AARCH64
        else -> Arch.X86_64
    }
}
