package com.nuvio.app

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

internal actual val isIos: Boolean = false
internal actual val isDesktop: Boolean = false
internal actual val isWindows: Boolean = false

internal actual val isDesktopRenderBackendConfigurable: Boolean = false
internal actual fun isDesktopOpenGlRendererEnabled(): Boolean = false
internal actual fun setDesktopOpenGlRendererEnabled(enabled: Boolean) = Unit

