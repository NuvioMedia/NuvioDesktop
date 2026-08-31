package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformBackHandler(
    enabled: Boolean,
    onHistoryBack: (() -> Unit)? = null,
    onBack: () -> Unit,
)
