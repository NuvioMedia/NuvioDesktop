package com.nuvio.app.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
actual fun PlatformForwardHandler(
    enabled: Boolean,
    onForward: () -> Unit,
) = Unit
