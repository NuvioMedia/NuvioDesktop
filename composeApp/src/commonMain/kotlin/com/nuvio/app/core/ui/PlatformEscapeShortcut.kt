package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

/**
 * Registers the handler invoked when the user presses Escape.
 *
 * On desktop this is wired to the AWT shortcut host so Escape reaches navigation even while a
 * card, list, or player surface holds focus. Other platforms ignore it.
 */
@Composable
internal expect fun PlatformEscapeShortcut(handler: () -> Unit)
