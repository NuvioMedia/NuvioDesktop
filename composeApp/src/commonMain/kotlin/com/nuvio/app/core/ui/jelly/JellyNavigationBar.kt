package com.nuvio.app.core.ui.jelly

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.FloatingNavigationItem
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.detectJellyTabGestures
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.visualNavIndex

@Composable
internal fun JellyNavigationBar(
    items: List<FloatingNavigationItem>,
    labelFraction: Float,
    modifier: Modifier = Modifier,
    compactSize: Boolean = false,
    glowStrength: Float = 1f,
    horizontalLabels: Boolean = false,
    iconSize: Dp = if (compactSize) 24.dp else 28.dp,
    surface: @Composable BoxScope.() -> Unit,
) {
    if (items.isEmpty()) return
    val accentColor = MaterialTheme.nuvio.colors.accent
    val selectedSurface = accentColor.copy(alpha = NuvioTokens.Opacity.selected)
    val currentItems by rememberUpdatedState(items)
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val selectedIndex = items.indexOfFirst { it.selected }
    val visualSelectedIndex = visualNavIndex(selectedIndex, items.size, isRtl)
    val motion = remember(items.size, isRtl) { JellyMotion(visualSelectedIndex, items.size) }
    val density = LocalDensity.current
    val currentIsRtl by rememberUpdatedState(isRtl)
    LaunchedEffect(visualSelectedIndex, items.size) {
        motion.select(visualSelectedIndex)
    }
    LaunchedEffect(motion.running) {
        if (!motion.running) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (motion.running) {
            withFrameNanos { now ->
                motion.advance((now - previous) / 1_000_000_000.0)
                previous = now
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged {
                motion.resize(it.width / density.density, it.height / density.density, items.size)
            }
            .pointerInput(motion, density, items.size, isRtl) {
                detectJellyTabGestures(motion, density.density, { currentItems }, { currentIsRtl })
            },
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    val frame = motion.frame
                    scaleX = frame.trackScale
                    scaleY = frame.trackScale
                },
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val frame = motion.frame
                        transformOrigin = TransformOrigin(
                            if (size.width > 0) frame.originX * density.density / size.width else 0.5f,
                            0.5f,
                        )
                        scaleX = frame.trackScaleX
                        translationY = frame.trackOffsetY * density.density
                    },
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { translationX = motion.frame.panelOffset * density.density },
                ) {
                    Box(
                        Modifier.matchParentSize()
                            .clip(RoundedCornerShape(50))
                            .drawWithContent {
                                drawContent()
                                drawJellyGlow(motion.frame, accentColor.copy(alpha = accentColor.alpha * glowStrength))
                            },
                    ) {
                        surface()
                    }
                    Box(
                        Modifier.matchParentSize().drawWithContent {
                            if (selectedIndex >= 0) {
                                clipPath(jellyPillPath(motion.frame, items.size), ClipOp.Difference) {
                                    this@drawWithContent.drawContent()
                                }
                            } else {
                                drawContent()
                            }
                        },
                    ) {
                        JellyTabRow(items, labelFraction, motion, active = false, compactSize = compactSize, modifier = Modifier.matchParentSize(), horizontalLabels = horizontalLabels, iconSize = iconSize)
                    }
                    if (selectedIndex >= 0) {
                        Box(
                            Modifier.matchParentSize()
                                .clearAndSetSemantics {}
                                .drawWithContent {
                                    drawJellyPill(
                                        motion.frame,
                                        items.size,
                                        selectedSurface,
                                        accentColor.copy(alpha = accentColor.alpha * glowStrength),
                                    ) { drawContent() }
                                },
                        ) {
                            JellyTabRow(items, labelFraction, motion, active = true, compactSize = compactSize, modifier = Modifier.matchParentSize(), horizontalLabels = horizontalLabels, iconSize = iconSize)
                        }
                    }
                    JellyTabTargets(items, labelFraction, motion, compactSize, Modifier.matchParentSize(), horizontalLabels, iconSize)
                }
            }
        }
    }

}
