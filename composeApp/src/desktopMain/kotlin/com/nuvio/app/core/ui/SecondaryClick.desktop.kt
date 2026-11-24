package com.nuvio.app.core.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.secondaryClickAt(onClick: ((Offset) -> Unit)?): Modifier {
    if (onClick == null) return this
    return this then SecondaryClickElement(onClick)
}

private data class SecondaryClickElement(
    private val onClick: (Offset) -> Unit,
) : ModifierNodeElement<SecondaryClickNode>() {
    override fun create(): SecondaryClickNode = SecondaryClickNode(onClick)

    override fun update(node: SecondaryClickNode) {
        // Swap callback in place without cancelling or restarting the pointerInput coroutine.
        node.onClick = onClick
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "secondaryClickAt"
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private class SecondaryClickNode(
    var onClick: (Offset) -> Unit,
) : DelegatingNode() {
    init {
        delegate(
            SuspendingPointerInputModifierNode {
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
            },
        )
    }
}
