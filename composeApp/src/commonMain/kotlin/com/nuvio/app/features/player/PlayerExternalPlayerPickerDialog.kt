package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

/**
 * Platform-specific dialog for selecting an external player from inside the in-app player UI.
 *
 * On desktop (Windows/Mac), the in-app video surface is rendered through a heavyweight
 * native Swing component (libVLC), which is always drawn above lightweight Compose
 * popups/dialogs hosted in the same window. The desktop actual therefore opens this
 * picker as a separate top-level dialog window with `alwaysOnTop = true` so that it
 * appears above the paused player surface.
 *
 * Mobile/iOS use the existing inline dialog as a fallback; they never trigger this
 * picker because the in-app external player flow launches the external app directly.
 */
@Composable
expect fun PlayerExternalPlayerPickerDialog(
    players: List<ExternalPlayerApp>,
    selectedPlayerId: String?,
    onPlayerSelected: (String) -> Unit,
    onDismiss: () -> Unit,
)
