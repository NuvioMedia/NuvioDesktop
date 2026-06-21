package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val entry = remember { DesktopNavigationHandlerRegistry.Entry() }

    SideEffect {
        entry.backEnabled = enabled
        entry.onBack = onBack
    }

    DisposableEffect(entry) {
        DesktopNavigationHandlerRegistry.register(entry)
        onDispose {
            DesktopNavigationHandlerRegistry.unregister(entry)
        }
    }
}

@Composable
actual fun PlatformForwardHandler(
    enabled: Boolean,
    onForward: () -> Unit,
) {
    val entry = remember { DesktopNavigationHandlerRegistry.Entry() }

    SideEffect {
        entry.forwardEnabled = enabled
        entry.onForward = onForward
    }

    DisposableEffect(entry) {
        DesktopNavigationHandlerRegistry.register(entry)
        onDispose {
            DesktopNavigationHandlerRegistry.unregister(entry)
        }
    }
}

private object DesktopNavigationHandlerRegistry {
    private const val BackSideButton = 4
    private const val ForwardSideButton = 5
    private const val LastSideButton = 9

    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private var listenerInstalled = false

    private val listener = AWTEventListener { event ->
        val mouseEvent = event as? MouseEvent ?: return@AWTEventListener
        if (mouseEvent.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
        if (mouseEvent.button !in BackSideButton..LastSideButton) return@AWTEventListener

        val navigation = when (mouseEvent.button) {
            ForwardSideButton -> NavigationDirection.Forward
            else -> NavigationDirection.Back
        }
        val callback = synchronized(lock) {
            when (navigation) {
                NavigationDirection.Back -> entries.asReversed()
                    .firstOrNull { it.backEnabled }
                    ?.onBack
                NavigationDirection.Forward -> entries.asReversed()
                    .firstOrNull { it.forwardEnabled }
                    ?.onForward
            }
        } ?: return@AWTEventListener

        mouseEvent.consume()
        SwingUtilities.invokeLater {
            callback()
        }
    }

    fun register(entry: Entry) {
        synchronized(lock) {
            if (entry in entries) return
            entries += entry
            if (!listenerInstalled) {
                Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
                listenerInstalled = true
            }
        }
    }

    fun unregister(entry: Entry) {
        synchronized(lock) {
            entries -= entry
            if (entries.isEmpty() && listenerInstalled) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
                listenerInstalled = false
            }
        }
    }

    class Entry {
        @Volatile
        var backEnabled: Boolean = false

        @Volatile
        var forwardEnabled: Boolean = false

        @Volatile
        var onBack: () -> Unit = {}

        @Volatile
        var onForward: () -> Unit = {}
    }

    private enum class NavigationDirection {
        Back,
        Forward,
    }
}
