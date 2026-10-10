package com.nuvio.app.core.ui

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.isDesktop

fun Modifier.nuvioFocusBorder(
    shape: Shape,
    borderWidth: Dp = 3.dp,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio
    this
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused && isDesktop) Modifier.border(
                width = borderWidth,
                color = tokens.colors.focusRing,
                shape = shape,
            ) else Modifier,
        )
}

/**
 * Moves focus to the first focusable element when a screen first appears.
 *
 * Compose Desktop only delivers key events to the focused node, so a screen entered without a
 * focus target ignores arrow keys until something is focused by pointer. Seeding focus on entry
 * makes every screen keyboard reachable immediately. No-op when the user already focused
 * something, so it never steals an existing selection.
 */
fun Modifier.nuvioAutoFocusFirst(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        if (isDesktop) {
            focusManager.clearFocus()
            focusManager.moveFocus(FocusDirection.Next)
        }
    }
    this
}
