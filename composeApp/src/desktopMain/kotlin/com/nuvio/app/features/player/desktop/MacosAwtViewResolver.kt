package com.nuvio.app.features.player.desktop

import java.awt.Component
import java.lang.reflect.Field
import java.lang.reflect.Method

internal object AwtNativeViewResolver {
    fun resolveNativeViewPointer(component: Component): Long =
        when (DesktopHostOs.current) {
            DesktopHostOs.MACOS -> MacosAwtViewResolver.resolveNativeViewPointer(component)
            DesktopHostOs.WINDOWS -> WindowsAwtViewResolver.resolveNativeViewPointer(component)
            DesktopHostOs.LINUX -> LinuxAwtViewResolver.resolveNativeViewPointer(component)
            else -> error("Native desktop playback is not implemented for ${DesktopHostOs.current}.")
        }
}

private object MacosAwtViewResolver {
    private val componentPeerField: Field by lazy {
        Component::class.java.getDeclaredField("peer").apply { isAccessible = true }
    }

    fun resolveNativeViewPointer(component: Component): Long {
        val peer = componentPeerField.get(component)
            ?: error("AWT component peer is not ready for native playback.")

        val platformWindow = invokeObject(peer, "getPlatformWindow")
        val contentView = invokeObject(platformWindow, "getContentView")
        val pointer = invokeLong(contentView, "getAWTView")
        if (pointer == 0L) {
            error("macOS AWT view pointer was zero.")
        }
        return pointer
    }

    private fun findMethod(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        error("Method $name was not found on ${type.name}.")
    }

    private fun invokeObject(target: Any, methodName: String): Any =
        findMethod(target.javaClass, methodName).invoke(target)
            ?: error("$methodName returned null.")

    private fun invokeLong(target: Any, methodName: String): Long =
        (findMethod(target.javaClass, methodName).invoke(target) as Number).toLong()
}

private object WindowsAwtViewResolver {
    private val componentPeerField: Field by lazy {
        Component::class.java.getDeclaredField("peer").apply { isAccessible = true }
    }

    fun resolveNativeViewPointer(component: Component): Long {
        val peer = componentPeerField.get(component)
            ?: error("AWT component peer is not ready for native playback.")

        val pointer = invokeLong(peer, "getHWnd")
        if (pointer == 0L) {
            error("Windows AWT HWND pointer was zero.")
        }
        return pointer
    }

    private fun findMethod(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        error("Method $name was not found on ${type.name}.")
    }

    private fun invokeLong(target: Any, methodName: String): Long =
        (findMethod(target.javaClass, methodName).invoke(target) as Number).toLong()
}

/**
 * Linux X11: resolves the native X11 Window ID from AWT peer.
 * This allows mpv to render directly into the X11 window with vo=gpu-next,
 * bypassing the expensive CPU frame copy pipeline.
 */
private object LinuxAwtViewResolver {
    private val componentPeerField: Field by lazy {
        Component::class.java.getDeclaredField("peer").apply { isAccessible = true }
    }

    fun resolveNativeViewPointer(component: Component): Long {
        if (DesktopHostOs.isWayland) {
            error("GPU-direct wid mode is not supported on Wayland; use SW rendering fallback.")
        }

        val peer = componentPeerField.get(component)
            ?: error("AWT component peer is not ready for native playback.")

        // On X11, the AWT peer (XComponentPeer / XCanvasPeer) exposes getWindow()
        // which returns the X11 Window (XID) as a long.
        val pointer = invokeLong(peer, "getWindow")
        if (pointer == 0L) {
            error("Linux AWT X11 window pointer was zero.")
        }
        return pointer
    }

    private fun findMethod(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        error("Method $name was not found on ${type.name}.")
    }

    private fun invokeLong(target: Any, methodName: String): Long =
        (findMethod(target.javaClass, methodName).invoke(target) as Number).toLong()
}
