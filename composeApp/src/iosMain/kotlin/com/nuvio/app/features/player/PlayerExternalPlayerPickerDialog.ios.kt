package com.nuvio.app.features.player

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import com.nuvio.app.features.settings.ExternalPlayerSelectionDialog

/**
 * iOS does not show this in-app picker (external playback is launched directly
 * via [ExternalPlayerLauncherEffect]). Provide a fallback that delegates to the
 * existing settings dialog so the function is fully resolvable on this target.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
actual fun PlayerExternalPlayerPickerDialog(
    players: List<ExternalPlayerApp>,
    selectedPlayerId: String?,
    onPlayerSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ExternalPlayerSelectionDialog(
        players = players,
        selectedPlayerId = selectedPlayerId,
        onPlayerSelected = onPlayerSelected,
        onDismiss = onDismiss,
    )
}
