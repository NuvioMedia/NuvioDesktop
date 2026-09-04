package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

internal actual fun Modifier.secondaryClickAt(onClick: ((Offset) -> Unit)?): Modifier = this
