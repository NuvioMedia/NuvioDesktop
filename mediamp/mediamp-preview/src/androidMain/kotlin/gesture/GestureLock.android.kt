package gesture

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import org.openani.mediamp.core.guesture.GestureLock

@PreviewLightDark
@Composable
private fun PreviewGestureLockLocked() {
    GestureLock(true, {})
}

@PreviewLightDark
@Composable
private fun PreviewGestureLockUnlocked() {
    GestureLock(false, {})
}
