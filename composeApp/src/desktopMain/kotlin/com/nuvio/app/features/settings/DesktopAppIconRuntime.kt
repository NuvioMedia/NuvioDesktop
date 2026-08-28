package com.nuvio.app.features.settings

import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

internal fun applyDesktopRuntimeIcon(
    hostOs: DesktopHostOs,
    image: Image,
    setWindowIconImages: (List<Image>) -> Unit,
    setMacosDockIcon: (Image) -> Unit = ::setMacosDockIcon,
) {
    setWindowIconImages(listOf(image))
    if (hostOs == DesktopHostOs.MACOS) {
        setMacosDockIcon(createPaddedMacosDockIcon(image))
    }
}

internal fun createPaddedMacosDockIcon(image: Image): BufferedImage {
    val width = image.getWidth(null).coerceAtLeast(1)
    val height = image.getHeight(null).coerceAtLeast(1)
    val contentWidth = (width * MACOS_DOCK_ICON_CONTENT_SCALE).roundToInt().coerceAtLeast(1)
    val contentHeight = (height * MACOS_DOCK_ICON_CONTENT_SCALE).roundToInt().coerceAtLeast(1)
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { canvas ->
        val graphics = canvas.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(
                image,
                (width - contentWidth) / 2,
                (height - contentHeight) / 2,
                contentWidth,
                contentHeight,
                null,
            )
        } finally {
            graphics.dispose()
        }
    }
}

private fun setMacosDockIcon(image: Image) {
    runCatching {
        if (Taskbar.isTaskbarSupported()) {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                taskbar.iconImage = image
            }
        }
    }.onFailure {
        System.err.println("Failed to update the macOS Dock icon: ${it.message}")
    }
}

private const val MACOS_DOCK_ICON_CONTENT_SCALE = 0.8
