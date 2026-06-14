/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */
@file:JvmName("MPVHandleAndroid")

package org.openani.mediamp.mpv

import android.view.Surface
import org.openani.mediamp.InternalMediampApi

@InternalMediampApi
external fun nAttachAndroidSurface(ptr: Long, surface: Surface): Boolean

@InternalMediampApi
external fun nDetachAndroidSurface(ptr: Long): Boolean

@kotlin.OptIn(InternalMediampApi::class)
actual fun attachSurface(ptr: Long, surface: Any): Boolean {
    check(surface is android.view.Surface) { "surface must be an android.view.Surface" }
    return nAttachAndroidSurface(ptr, surface)
}

@kotlin.OptIn(InternalMediampApi::class)
actual fun detachSurface(ptr: Long): Boolean {
    return nDetachAndroidSurface(ptr)
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