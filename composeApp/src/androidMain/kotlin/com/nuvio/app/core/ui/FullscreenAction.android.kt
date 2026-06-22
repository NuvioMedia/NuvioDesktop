package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

internal actual val isFullscreenActionSupported: Boolean = false

@Composable
internal actual fun isFullscreenActionActive(): Boolean = false

internal actual fun toggleFullscreenAction() = Unit
