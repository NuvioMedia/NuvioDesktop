package com.nuvio.app.features.player.desktop

import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

/**
 * Keeps a window's maximized bounds pointed at the screen it is actually on.
 *
 * AWT constrains maximizing with bounds derived from the primary screen, so the
 * first maximize after a window crosses to a monitor with a different scale
 * factor is the wrong size. Setting them explicitly, and again on each change of
 * screen, hands AWT the right rectangle before it is asked for one.
 *
 * Upstream: JDK-8176359, JDK-8231564, JDK-8187616, JDK-8263086.
 *
 * Windows only; macOS has its own maximize semantics.
 */
internal fun Window.trackMaximizedBoundsForCurrentScreen(): () -> Unit {
    val frame = this as? Frame ?: return {}
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return {}

    var lastConfiguration: GraphicsConfiguration? = null

    fun apply() {
        val configuration = frame.graphicsConfiguration ?: return
        // Writing maximized bounds mid-maximize can disturb the frame, so only
        // a real change of screen triggers a recompute.
        if (configuration === lastConfiguration) return
        lastConfiguration = configuration
        frame.maximizedBounds = configuration.workArea()
    }

    apply()
    val listener = object : ComponentAdapter() {
        override fun componentMoved(event: ComponentEvent) = apply()
        override fun componentResized(event: ComponentEvent) = apply()
    }
    frame.addComponentListener(listener)
    return { frame.removeComponentListener(listener) }
}

/**
 * The screen less whatever the desktop reserves, so a maximized window still
 * stops at the taskbar.
 *
 * In the same user space `setMaximizedBounds` is documented to take. A JDK
 * exhibiting JDK-8187616 would need `defaultTransform` applied here.
 */
private fun GraphicsConfiguration.workArea(): Rectangle {
    val screen = bounds
    val insets = runCatching {
        Toolkit.getDefaultToolkit().getScreenInsets(this)
    }.getOrNull() ?: return Rectangle(screen)
    return Rectangle(
        screen.x + insets.left,
        screen.y + insets.top,
        (screen.width - insets.left - insets.right).coerceAtLeast(1),
        (screen.height - insets.top - insets.bottom).coerceAtLeast(1),
    )
}
