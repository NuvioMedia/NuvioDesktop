package com.nuvio.app.features.player.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Insets
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
    private val onCloseRequested: () -> Unit,
    private val onResized: () -> Unit = {},
    private val onFocusGained: () -> Unit = {},
) : JDialog(ownerWindow) {
    /** Heavyweight host required by the native HWND/NSView reparenting bridge. */
    val videoHolderPanel = Panel(BorderLayout())

    var aspectRatio: Float = 16f / 9f

    override fun getInsets(): Insets = Insets(0, 0, 0, 0)

    init {
        isUndecorated = true
        isResizable = true
        focusableWindowState = true
        background = Color.BLACK
        minimumSize = Dimension(320, 180)
        rootPane.border = null
        videoHolderPanel.background = Color.BLACK
        contentPane = videoHolderPanel
        title = ""
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) {
                onCloseRequested()
            }
        })
        addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(event: WindowEvent) {
                onFocusGained()
            }
        })
        videoHolderPanel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                if (DesktopHostOs.current != DesktopHostOs.WINDOWS) {
                    onResized()
                }
            }
        })
        addComponentListener(object : ComponentAdapter() {
            private var resizing = false

            override fun componentResized(event: ComponentEvent) {
                if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
                    return
                }
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
                onResized()
            }
        })
    }

    fun updateWindowTitle(windowTitle: String) {
        title = windowTitle
    }
}
