package com.nuvio.app.core.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.secondaryClickAt(onClick: ((Offset) -> Unit)?): Modifier {
    if (onClick == null) return this

    return pointerInput(onClick) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) {
                    val position = event.changes.firstOrNull()?.position ?: Offset.Zero
                    event.changes.forEach { change -> change.consume() }
                    onClick(position)
                }
            }
        }
    }
}
