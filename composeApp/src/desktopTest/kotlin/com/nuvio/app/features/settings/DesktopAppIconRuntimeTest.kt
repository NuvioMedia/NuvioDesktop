package com.nuvio.app.features.settings

import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.awt.Color
import java.awt.Image
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopAppIconRuntimeTest {
    private val image = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB).apply {
        createGraphics().also { graphics ->
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.dispose()
        }
    }

    @Test
    fun `macOS applies selected icon to window and Dock`() {
        val windowIcons = mutableListOf<List<Image>>()
        val dockIcons = mutableListOf<Image>()

        applyDesktopRuntimeIcon(
            hostOs = DesktopHostOs.MACOS,
            image = image,
            setWindowIconImages = windowIcons::add,
            setMacosDockIcon = dockIcons::add,
        )

        assertEquals(1, windowIcons.size)
        assertEquals(1, windowIcons.single().size)
        assertSame(image, windowIcons.single().single())
        assertEquals(1, dockIcons.size)
        assertNotSame(image, dockIcons.single())
        val dockIcon = dockIcons.single() as BufferedImage
        assertEquals(0, dockIcon.getRGB(0, 0).ushr(24))
        assertEquals(255, dockIcon.getRGB(dockIcon.width / 2, dockIcon.height / 2).ushr(24))
    }

    @Test
    fun `other desktop platforms do not override taskbar icon`() {
        val dockIcons = mutableListOf<Image>()

        applyDesktopRuntimeIcon(
            hostOs = DesktopHostOs.WINDOWS,
            image = image,
            setWindowIconImages = {},
            setMacosDockIcon = dockIcons::add,
        )

        assertTrue(dockIcons.isEmpty())
    }
}
