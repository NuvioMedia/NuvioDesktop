package com.nuvio.app

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private const val DESKTOP_ENTRY_NAME = "nuvio-desktop.desktop"
private const val APP_NAME = "Nuvio"
private const val ICON_NAME = "nuvio-app-icon"
private const val WM_CLASS = "com.nuvio.app.MainKt"

private val DESKTOP_ENTRY_CONTENT = """
    [Desktop Entry]
    Name=$APP_NAME
    Exec=$APP_NAME
    Icon=$ICON_NAME
    Type=Application
    Categories=AudioVideo;Video;Player;
    Comment=$APP_NAME Media Player
    Terminal=false
    StartupWMClass=$WM_CLASS
""".trimIndent()

/**
 * Installs a .desktop file and application icon into the user's local directories
 * so that GNOME (and other freedesktop-compliant DEs) can match the running window
 * to the correct dock/taskbar icon via WM_CLASS.
 */
fun installLinuxDesktopIntegration() {
    if (!System.getProperty("os.name").contains("linux", ignoreCase = true)) return

    runCatching {
        val home = System.getProperty("user.home") ?: return
        val localShare = File(home, ".local/share")

        installDesktopEntry(localShare)
        installIcon(localShare)
        updateDesktopDatabase(localShare)
    }
}

private fun installDesktopEntry(localShare: File) {
    val applicationsDir = File(localShare, "applications")
    if (!applicationsDir.exists()) return

    val desktopFile = File(applicationsDir, DESKTOP_ENTRY_NAME)
    if (desktopFile.exists()) {
        val existing = desktopFile.readText()
        if (existing.contains(WM_CLASS)) return
    }

    runCatching {
        desktopFile.writeText(DESKTOP_ENTRY_CONTENT)
    }
}

private fun installIcon(localShare: File) {
    val iconsDir = File(localShare, "icons/hicolor/256x256/apps")
    if (!iconsDir.exists()) {
        iconsDir.mkdirs()
    }

    val targetIcon = File(iconsDir, "$ICON_NAME.png")
    if (targetIcon.exists()) return

    val classLoader = Thread.currentThread().contextClassLoader
    val iconSizes = listOf(256, 128, 96, 64, 48, 32)

    for (size in iconSizes) {
        val resource = classLoader.getResourceAsStream("icons/nuvio_$size.png") ?: continue
        resource.use { input ->
            val image: BufferedImage = ImageIO.read(input) ?: continue
            if (image.width == 256 && image.height == 256) {
                ImageIO.write(image, "png", targetIcon)
                return
            }
        }
    }

    val fallbackResource = classLoader.getResourceAsStream("icons/nuvio-app-icon.png") ?: return
    fallbackResource.use { input ->
        val image: BufferedImage = ImageIO.read(input) ?: return
        ImageIO.write(image, "png", targetIcon)
    }
}

private fun updateDesktopDatabase(localShare: File) {
    runCatching {
        ProcessBuilder("update-desktop-database", File(localShare, "applications").absolutePath)
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }
}
