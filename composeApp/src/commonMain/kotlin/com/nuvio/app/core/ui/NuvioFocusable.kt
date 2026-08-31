package com.nuvio.app.core.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.nuvioFocusBorder(
    shape: Shape,
    borderWidth: Dp = 3.dp,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio
    this
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .then(
            if (focused) Modifier.border(
                width = borderWidth,
                color = tokens.colors.focusRing,
                shape = shape,
            ) else Modifier,
        )
}
