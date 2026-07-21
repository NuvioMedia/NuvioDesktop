package com.nuvio.app

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

internal expect val isIos: Boolean
internal expect val isDesktop: Boolean
internal expect val isWindows: Boolean

/** Whether the desktop UI render backend (Direct3D vs OpenGL) can be chosen at runtime. Windows only. */
internal expect val isDesktopRenderBackendConfigurable: Boolean

/** Reads the persisted "use OpenGL for the desktop UI" preference (default false = Direct3D). */
internal expect fun isDesktopOpenGlRendererEnabled(): Boolean

/** Persists the "use OpenGL for the desktop UI" preference. Takes effect after an app restart. */
internal expect fun setDesktopOpenGlRendererEnabled(enabled: Boolean)

