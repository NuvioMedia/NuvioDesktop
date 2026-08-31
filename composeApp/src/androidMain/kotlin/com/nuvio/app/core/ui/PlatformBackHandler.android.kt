package com.nuvio.app.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onHistoryBack: (() -> Unit)?,
    onBack: () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onBack)
}
