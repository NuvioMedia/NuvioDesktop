package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit

@Composable
actual fun PlatformForwardHandler(
    enabled: Boolean,
    onForward: () -> Unit,
) = Unit
