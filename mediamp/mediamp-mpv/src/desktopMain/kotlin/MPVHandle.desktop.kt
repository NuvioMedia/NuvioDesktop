/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */
@file:JvmName("MPVHandleDesktop")

package org.openani.mediamp.mpv

import org.openani.mediamp.InternalMediampApi

@InternalMediampApi
external fun nCreateRenderContext(ptr: Long, devicePtr: Long, contextPtr: Long, drawablePtr: Long = 0L): Boolean

@InternalMediampApi
external fun nDestroyRenderContext(ptr: Long): Boolean

@InternalMediampApi
external fun nCreateTexture(ptr: Long, width: Int, height: Int): Int

@InternalMediampApi
external fun nReleaseTexture(ptr: Long): Boolean

@InternalMediampApi
external fun nRenderFrameToTexture(ptr: Long): Boolean

@InternalMediampApi
external fun nDebugRenderSolid(ptr: Long, red: Float, green: Float, blue: Float, alpha: Float): Boolean

@InternalMediampApi
external fun nReadTextureStats(ptr: Long): String

@OptIn(InternalMediampApi::class)
internal actual fun attachSurface(ptr: Long, surface: Any): Boolean {
    error("only implemented on Android")
}

@OptIn(InternalMediampApi::class)
internal actual fun detachSurface(ptr: Long): Boolean {
    error("only implemented on Android")
}

@OptIn(InternalMediampApi::class)
actual fun createRenderContext(ptr: Long, devicePtr: Long, contextPtr: Long, drawablePtr: Long): Boolean {
    return nCreateRenderContext(ptr, devicePtr, contextPtr, drawablePtr)
}

@OptIn(InternalMediampApi::class)
actual fun destroyRenderContext(ptr: Long): Boolean {
    return nDestroyRenderContext(ptr)
}

@OptIn(InternalMediampApi::class)
actual fun createTexture(ptr: Long, width: Int, height: Int): Int {
    return nCreateTexture(ptr, width, height)
}

@OptIn(InternalMediampApi::class)
actual fun releaseTexture(ptr: Long): Boolean {
    return nReleaseTexture(ptr)
}

@OptIn(InternalMediampApi::class)
actual fun renderFrameToTexture(ptr: Long): Boolean {
    return nRenderFrameToTexture(ptr)
}

@OptIn(InternalMediampApi::class)
actual fun debugRenderSolid(ptr: Long, red: Float, green: Float, blue: Float, alpha: Float): Boolean {
    return nDebugRenderSolid(ptr, red, green, blue, alpha)
}

@OptIn(InternalMediampApi::class)
actual fun readTextureStats(ptr: Long): String {
    return nReadTextureStats(ptr)
}
