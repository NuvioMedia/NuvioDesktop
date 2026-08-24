package com.nuvio.app.features.player.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Panel
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog

/** Borderless, always-on-top video surface used by desktop PiP. */
internal class DesktopPlayerPipWindow(
    ownerWindow: Window?,
    private val onRestoreRequested: () -> Unit,
    private val onCloseRequested: () -> Unit,
) : JDialog(ownerWindow) {
    /** Heavyweight host required by the native HWND/NSView reparenting bridge. */
    val videoHolderPanel = Panel(BorderLayout())

    var aspectRatio: Float = 16f / 9f

    init {
        isUndecorated = true
        isResizable = true
        focusableWindowState = true
        background = Color.BLACK
        minimumSize = Dimension(320, 180)
        videoHolderPanel.background = Color.BLACK
        contentPane = videoHolderPanel
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) {
                onCloseRequested()
            }
        })

        addComponentListener(object : ComponentAdapter() {
            private var resizing = false

            override fun componentResized(event: ComponentEvent) {
                if (resizing) return
                resizing = true
                try {
                    val width = width.coerceAtLeast(minimumSize.width)
                    val height = (width / aspectRatio).toInt().coerceAtLeast(minimumSize.height)
                    if (this@DesktopPlayerPipWindow.width != width ||
                        this@DesktopPlayerPipWindow.height != height
                    ) {
                        setSize(width, height)
                    }
                } finally {
                    resizing = false
                }
            }
        })
    }

    /** Kept as a no-op API so dynamic localization remains owned by player state. */
    fun updateLocalizedLabels(windowTitle: String, restoreTooltip: String, closeTooltip: String) = Unit
}
