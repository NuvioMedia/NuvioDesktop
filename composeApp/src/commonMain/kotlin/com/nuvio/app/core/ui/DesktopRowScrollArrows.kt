package com.nuvio.app.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.isDesktop
import kotlinx.coroutines.launch

private const val RowArrowScrollFraction = 0.8f
private val RowArrowSize = 44.dp
private val RowArrowIconSize = 28.dp
private val RowArrowEdgeInset = 8.dp

/**
 * On desktop, draws previous and next buttons over a horizontal row while the pointer is on it, so the row
 * can be paged with a click instead of a drag. Each button shows only when the row can scroll that way.
 *
 * Put it before [nuvioHorizontalScrollBleed] so the buttons sit at the row's visible edges.
 */
@Composable
internal fun Modifier.nuvioDesktopRowScrollArrows(state: LazyListState): Modifier {
    if (!isDesktop) return this

    val layoutDirection = LocalLayoutDirection.current
    val colorScheme = MaterialTheme.colorScheme
    val backwardPainter = rememberVectorPainter(Icons.AutoMirrored.Rounded.KeyboardArrowLeft)
    val forwardPainter = rememberVectorPainter(Icons.AutoMirrored.Rounded.KeyboardArrowRight)
    val scope = rememberCoroutineScope()

    var hovered by remember { mutableStateOf(false) }
    var pressedDirection by remember { mutableIntStateOf(0) }
    val canScrollBackward by remember(state) { derivedStateOf { state.canScrollBackward } }
    val canScrollForward by remember(state) { derivedStateOf { state.canScrollForward } }
    val backwardAlpha by animateFloatAsState(if (hovered && canScrollBackward) 1f else 0f, label = "row_arrow_backward")
    val forwardAlpha by animateFloatAsState(if (hovered && canScrollForward) 1f else 0f, label = "row_arrow_forward")
    val currentCanScrollBackward by rememberUpdatedState(hovered && canScrollBackward)
    val currentCanScrollForward by rememberUpdatedState(hovered && canScrollForward)

    // Returns -1 for the backward button, 1 for the forward button and 0 elsewhere.
    fun directionAt(position: Offset, size: Size, density: Float): Int {
        val radius = RowArrowSize.value * density / 2f
        val inset = RowArrowEdgeInset.value * density
        val centerY = size.height / 2f
        val startX = inset + radius
        val endX = size.width - inset - radius
        val (backwardX, forwardX) = if (layoutDirection == LayoutDirection.Rtl) endX to startX else startX to endX
        fun inside(centerX: Float) = (position - Offset(centerX, centerY)).getDistance() <= radius
        return when {
            currentCanScrollBackward && inside(backwardX) -> -1
            currentCanScrollForward && inside(forwardX) -> 1
            else -> 0
        }
    }

    return this
        .pointerInput(state, layoutDirection) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    val rowSize = Size(size.width.toFloat(), size.height.toFloat())
                    when (event.type) {
                        PointerEventType.Enter, PointerEventType.Move -> hovered = true
                        PointerEventType.Exit -> hovered = false
                        PointerEventType.Press -> {
                            pressedDirection = directionAt(change.position, rowSize, density)
                            if (pressedDirection != 0) change.consume()
                        }
                        PointerEventType.Release -> if (pressedDirection != 0) {
                            change.consume()
                            val direction = pressedDirection
                            pressedDirection = 0
                            if (directionAt(change.position, rowSize, density) == direction) {
                                scope.launch {
                                    state.animateScrollBy(
                                        direction * state.layoutInfo.viewportSize.width * RowArrowScrollFraction,
                                    )
                                }
                            }
                        }
                    }
                    if (pressedDirection != 0) change.consume()
                }
            }
        }
        .drawWithContent {
            drawContent()
            val radius = RowArrowSize.toPx() / 2f
            val inset = RowArrowEdgeInset.toPx()
            val iconSize = RowArrowIconSize.toPx()
            val centerY = size.height / 2f
            val startX = inset + radius
            val endX = size.width - inset - radius
            val (backwardX, forwardX) = if (layoutDirection == LayoutDirection.Rtl) endX to startX else startX to endX
            listOf(
                Triple(backwardX, backwardAlpha, backwardPainter),
                Triple(forwardX, forwardAlpha, forwardPainter),
            ).forEach { (centerX, alpha, painter) ->
                if (alpha <= 0f) return@forEach
                drawCircle(
                    color = colorScheme.surfaceVariant.copy(alpha = 0.92f),
                    radius = radius,
                    center = Offset(centerX, centerY),
                    alpha = alpha,
                )
                translate(left = centerX - iconSize / 2f, top = centerY - iconSize / 2f) {
                    with(painter) {
                        draw(
                            size = Size(iconSize, iconSize),
                            alpha = alpha,
                            colorFilter = ColorFilter.tint(colorScheme.onSurface),
                        )
                    }
                }
            }
        }
}
