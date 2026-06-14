/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.openani.mediamp.InternalMediampApi

internal actual fun attachSurface(ptr: Long, surface: Any): Boolean {
    TODO("Not yet implemented")
}

internal actual fun detachSurface(ptr: Long): Boolean {
    TODO()
}

actual fun createRenderContext(ptr: Long, devicePtr: Long, contextPtr: Long, @Suppress("UNUSED_PARAMETER") drawablePtr: Long): Boolean {
    error("only implemented on desktop")
}

actual fun destroyRenderContext(ptr: Long): Boolean {
    error("only implemented on desktop")
}

actual fun createTexture(ptr: Long, width: Int, height: Int): Int {
    error("only implemented on desktop")
}

actual fun releaseTexture(ptr: Long): Boolean {
    error("only implemented on desktop")
}

@OptIn(InternalMediampApi::class)
actual fun renderFrameToTexture(ptr: Long): Boolean {
    error("only implemented on desktop")
}