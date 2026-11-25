package com.nuvio.app.core.ui

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

internal val LocalCardDepthStyleUiState = compositionLocalOf<CardDepthStyleUiState?> { null }

@Composable
fun rememberCardDepthStyleUiState(): CardDepthStyleUiState {
    LocalCardDepthStyleUiState.current?.let { return it }
    CardDepthStyleRepository.ensureLoaded()
    val uiState by CardDepthStyleRepository.uiState.collectAsState()
    return uiState
}

@Composable
fun Modifier.nuvioCardDepth(
    shape: Shape,
    surface: NuvioCardDepthSurface,
    fallbackBorderAlpha: Float = 0f,
): Modifier {
    val state = rememberCardDepthStyleUiState()
    if (!state.isEnabledFor(surface)) {
        return if (fallbackBorderAlpha > 0f) {
            border(
                width = 1.dp,
                color = Color.White.copy(alpha = fallbackBorderAlpha),
                shape = shape,
            )
        } else {
            this
        }
    }

    return cardDepthVisual(
        shape = shape,
        edgeStrength = state.edgeStrength.toFloat(),
        sheenStrength = state.sheenStrength.toFloat(),
        edgeCoverage = state.edgeCoverage.toFloat(),
    )
}

fun Modifier.cardDepthVisual(
    shape: Shape,
    edgeStrength: Float,
    sheenStrength: Float,
    edgeCoverage: Float = DefaultCardDepthEdgeCoverage.toFloat(),
): Modifier {
    val edgeTop = edgeStrength.coerceIn(0f, 100f) / 100f
    val sheen = sheenStrength.coerceIn(0f, 100f) / 100f
    val coverage = edgeCoverage.coerceIn(0f, 100f) / 100f
    if (edgeTop <= 0f && sheen <= 0f) return this

    // Combine border and sheen into one cached draw pass and use hardware drawRoundRect for
    // rounded cards instead of per-card AA stencil clipPath passes on every scroll frame.
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val borderWidthPx = 1.dp.toPx()
        val borderBrush = if (edgeTop > 0f) {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = edgeTop),
                    Color.White.copy(alpha = edgeTop * (0.33f + 0.67f * coverage)),
                    Color.White.copy(alpha = edgeTop * coverage),
                ),
                startY = 0f,
                endY = size.height,
            )
        } else {
            null
        }
        val borderStroke = if (borderBrush != null) Stroke(width = borderWidthPx) else null

        val sheenHeight = if (sheen > 0f) size.height * 0.22f else 0f
        val sheenSize = if (sheenHeight > 0f) Size(size.width, sheenHeight) else null
        val sheenBrush = if (sheenHeight > 0f) {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = sheen),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = sheenHeight,
            )
        } else {
            null
        }
        val roundRect = (outline as? Outline.Rounded)?.roundRect
        val canFastSheen = sheenHeight > 0f &&
            roundRect != null &&
            roundRect.topLeftCornerRadius == roundRect.topRightCornerRadius &&
            roundRect.topLeftCornerRadius.y <= sheenHeight / 2f &&
            roundRect.topLeftCornerRadius.x <= size.width / 2f
        val sheenCornerRadius = if (canFastSheen) roundRect!!.topLeftCornerRadius else null
        val sheenClipPath = if (sheenBrush != null && !canFastSheen && outline !is Outline.Rectangle) {
            if (outline is Outline.Generic) outline.path else Path().apply { addOutline(outline) }
        } else {
            null
        }
        val uniformCornerRadius = roundRect?.takeIf {
            it.topLeftCornerRadius == it.topRightCornerRadius &&
                it.topLeftCornerRadius == it.bottomLeftCornerRadius &&
                it.topLeftCornerRadius == it.bottomRightCornerRadius
        }?.topLeftCornerRadius
        val halfStroke = borderWidthPx / 2f
        val insetBorderTopLeft = Offset(halfStroke, halfStroke)
        val insetBorderSize = Size(
            (size.width - borderWidthPx).coerceAtLeast(0f),
            (size.height - borderWidthPx).coerceAtLeast(0f),
        )
        val insetBorderRadius = uniformCornerRadius?.let {
            CornerRadius(
                (it.x - halfStroke).coerceAtLeast(0f),
                (it.y - halfStroke).coerceAtLeast(0f),
            )
        }

        onDrawWithContent {
            drawContent()
            if (sheenBrush != null && sheenSize != null) {
                when {
                    sheenCornerRadius != null -> drawRoundRect(
                        brush = sheenBrush,
                        size = sheenSize,
                        cornerRadius = sheenCornerRadius,
                    )
                    sheenClipPath != null -> clipPath(sheenClipPath) {
                        drawRect(brush = sheenBrush, size = sheenSize)
                    }
                    else -> drawRect(brush = sheenBrush, size = sheenSize)
                }
            }
            if (borderBrush != null && borderStroke != null) {
                when {
                    insetBorderRadius != null -> drawRoundRect(
                        brush = borderBrush,
                        topLeft = insetBorderTopLeft,
                        size = insetBorderSize,
                        cornerRadius = insetBorderRadius,
                        style = borderStroke,
                    )
                    outline is Outline.Rectangle -> drawRect(
                        brush = borderBrush,
                        topLeft = insetBorderTopLeft,
                        size = insetBorderSize,
                        style = borderStroke,
                    )
                    else -> drawOutline(
                        outline = outline,
                        brush = borderBrush,
                        style = borderStroke,
                    )
                }
            }
        }
    }
}
