package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)

@Composable
expect fun PlatformForwardHandler(
    enabled: Boolean,
    onForward: () -> Unit,
)
