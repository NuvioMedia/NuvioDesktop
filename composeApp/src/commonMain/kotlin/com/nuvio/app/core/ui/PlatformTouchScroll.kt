package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier

/** Attach to each independent UI root: window, popup, dialog, or menu. */
internal expect fun Modifier.platformTouchScroll(): Modifier

// AWT exposes one primary mouse sequence. Retain ownership if a Compose root
// disappears during a touch drag, so its remaining events cannot become a tap
// in the newly exposed root. Only the Windows input bridge sets this flag.
internal object PlatformTouchScrollState {
    var isDragging: Boolean = false
}
