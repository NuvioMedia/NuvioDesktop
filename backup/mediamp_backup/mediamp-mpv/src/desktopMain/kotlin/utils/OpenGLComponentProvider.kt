/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.openani.mediamp.mpv.utils

import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.context.ContextHandler
import org.jetbrains.skiko.context.OpenGLContextHandler

class OpenGLComponentProvider(private val skiaLayer: SkiaLayer) {
    private val redrawer = skiaLayer.redrawer ?: error("SkiaLayer redrawer is null")
    private val redrawerClass = redrawer::class.java

    private val isLinux: Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("nux") == true

    // On Windows: WindowsOpenGLRedrawer has both "device" (HDC) and "context" (HGLRC)
    // On Linux: LinuxOpenGLRedrawer has "context" (GLXContext) but no "device" field
    private val deviceHandleField = if (!isLinux) {
        redrawerClass.getDeclaredField("device").also { it.isAccessible = true }
    } else null

    private val glContextHandleField = redrawerClass
        .getDeclaredField("context")
        .also { it.isAccessible = true }

    private val contextHandlerHandleField = redrawerClass
        .getDeclaredField("contextHandler")
        .also { it.isAccessible = true }
    private val directContextHandler = ContextHandler::class.java
        .getDeclaredField("context")
        .also { it.isAccessible = true }

    private val x11Display: Long get() {
        if (!isLinux) return 0L
        val backedLayer = skiaLayer.backedLayer ?: return 0L
        return try {
            val ktClass = Class.forName("org.jetbrains.skiko.AWTLinuxDrawingSurfaceKt")
            val hwLayerClass = Class.forName("org.jetbrains.skiko.HardwareLayer")
            val dsClass = Class.forName("org.jetbrains.skiko.LinuxDrawingSurface")

            val lock = ktClass.getMethod("lockLinuxDrawingSurface", hwLayerClass)
            val unlock = ktClass.getMethod("unlockLinuxDrawingSurface", dsClass)
            val getDisplay = dsClass.getMethod("getDisplay")

            val ds = lock.invoke(null, backedLayer)
            try {
                getDisplay.invoke(ds) as Long
            } finally {
                unlock.invoke(null, ds)
            }
        } catch (_: Exception) {
            0L
        }
    }

    // On Linux, glDevice returns the X11 Display* so the native code can use
    // Skiko's display connection for glXMakeCurrent (instead of XOpenDisplay).
    val glDevice: Long get() = if (isLinux) x11Display else (deviceHandleField?.getLong(redrawer) ?: 0L)
    val glContext: Long get() = glContextHandleField.getLong(redrawer)
    val glDrawable: Long get() {
        if (!isLinux) return 0L
        val backedLayer = skiaLayer.backedLayer ?: return 0L
        return try {
            val ktClass = Class.forName("org.jetbrains.skiko.AWTLinuxDrawingSurfaceKt")
            val hwLayerClass = Class.forName("org.jetbrains.skiko.HardwareLayer")
            val dsClass = Class.forName("org.jetbrains.skiko.LinuxDrawingSurface")
            val lock = ktClass.getMethod("lockLinuxDrawingSurface", hwLayerClass)
            val unlock = ktClass.getMethod("unlockLinuxDrawingSurface", dsClass)
            val getWindow = dsClass.getMethod("getWindow")
            val ds = lock.invoke(null, backedLayer)
            try {
                getWindow.invoke(ds) as Long
            } finally {
                unlock.invoke(null, ds)
            }
        } catch (_: Exception) {
            0L
        }
    }
    val contextSignature: String get() = "$glDevice:$glContext:$glDrawable"
    val contentScale: Float get() = skiaLayer.contentScale
    val currentDpi: Int get() = skiaLayer.currentDPI

    val directContext: DirectContext
        get() = (contextHandlerHandleField.get(redrawer) as OpenGLContextHandler)
            .let { directContextHandler.get(it) as DirectContext }
}
