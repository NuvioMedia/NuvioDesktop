package com.nuvio.app.core.window

import co.touchlab.kermit.Logger
import java.awt.AWTException
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon

internal class DesktopSystemTrayIcon(
    private val image: Image,
    private val openLabel: String,
    private val quitLabel: String,
) {
    private val log = Logger.withTag("DesktopSystemTray")
    private var trayIcon: TrayIcon? = null

    val isInstalled: Boolean
        get() = trayIcon != null

    fun install(
        onOpen: () -> Unit,
        onQuit: () -> Unit,
    ): Boolean {
        if (!SystemTray.isSupported()) return false

        val popupMenu = PopupMenu().apply {
            add(MenuItem(openLabel).apply { addActionListener { onOpen() } })
            addSeparator()
            add(MenuItem(quitLabel).apply { addActionListener { onQuit() } })
        }
        val icon = TrayIcon(image, "Nuvio", popupMenu).apply {
            isImageAutoSize = true
            addActionListener { onOpen() }
        }

        return try {
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            true
        } catch (error: AWTException) {
            log.e(error) { "Failed to install Windows system tray icon" }
            false
        }
    }

    fun remove() {
        val icon = trayIcon ?: return
        SystemTray.getSystemTray().remove(icon)
        trayIcon = null
    }
}

internal fun shouldHideWindowToTray(
    closeToTrayEnabled: Boolean,
    trayIconInstalled: Boolean,
): Boolean = closeToTrayEnabled && trayIconInstalled
