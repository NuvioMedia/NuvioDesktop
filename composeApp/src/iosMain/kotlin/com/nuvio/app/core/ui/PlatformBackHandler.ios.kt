package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onHistoryBack: (() -> Unit)?,
    onBack: () -> Unit,
) = Unit
