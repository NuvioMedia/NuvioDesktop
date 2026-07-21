package com.nuvio.app

class DesktopPlatform : Platform {
    override val name: String = "Desktop ${System.getProperty("os.name").orEmpty()}".trim()
}

actual fun getPlatform(): Platform = DesktopPlatform()

internal actual val isIos: Boolean = false
internal actual val isDesktop: Boolean = true
internal actual val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

internal actual val isDesktopRenderBackendConfigurable: Boolean = isWindows
internal actual fun isDesktopOpenGlRendererEnabled(): Boolean =
    com.nuvio.app.features.player.desktop.DesktopRenderSettings.isOpenGlEnabled()
internal actual fun setDesktopOpenGlRendererEnabled(enabled: Boolean) =
    com.nuvio.app.features.player.desktop.DesktopRenderSettings.setOpenGlEnabled(enabled)

