package com.nuvio.app.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.beans.PropertyChangeListener

internal actual object AppForegroundMonitor {
    actual fun events(): Flow<AppVisibility> = callbackFlow {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val listener = PropertyChangeListener { event ->
            trySend(
                if (event.newValue is Window) AppVisibility.Foreground else AppVisibility.Background,
            )
        }

        trySend(
            if (focusManager.activeWindow != null) AppVisibility.Foreground else AppVisibility.Background,
        )
        focusManager.addPropertyChangeListener("activeWindow", listener)
        awaitClose {
            focusManager.removePropertyChangeListener("activeWindow", listener)
        }
    }.distinctUntilChanged()
}
