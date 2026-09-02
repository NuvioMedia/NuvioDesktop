package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

internal fun Modifier.secondaryClick(onClick: (() -> Unit)?): Modifier =
    secondaryClickAt(onClick?.let { action -> { action() } })

internal expect fun Modifier.secondaryClickAt(onClick: ((Offset) -> Unit)?): Modifier
