package com.nuvio.app.core.ui.jelly

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

/**
 * Builds the track for a centered horizontal tab bar whose tabs grow from [compactTabWidth] to
 * [compactTabWidth] + [labelWidth] as [fraction] goes from 0 to 1. Both widths are capped at
 * [availableWidth], in pixels.
 */
internal fun Density.horizontalTabTrack(
    count: Int,
    compactTabWidth: Dp,
    labelWidth: Dp,
    availableWidth: Int,
    fraction: Float,
    direction: LayoutDirection,
): HorizontalTabTrack {
    val compactWidth = compactTabWidth * count + JellyTabInset * 2
    val expandedWidth = compactWidth + labelWidth * count
    return HorizontalTabTrack(
        availableWidth = availableWidth,
        compactWidth = compactWidth.roundToPx().coerceAtMost(availableWidth),
        expandedWidth = expandedWidth.roundToPx().coerceAtMost(availableWidth),
        inset = JellyTabInset.roundToPx(),
        count = count,
        fraction = fraction,
        direction = direction,
    )
}

/**
 * Pixel layout of a centered horizontal tab track. Every row of the bar asks the same track for its
 * slots, so the rows share identical tab boundaries.
 */
internal class HorizontalTabTrack(
    private val availableWidth: Int,
    private val compactWidth: Int,
    private val expandedWidth: Int,
    private val inset: Int,
    private val count: Int,
    fraction: Float,
    private val direction: LayoutDirection,
) {
    val width = lerp(compactWidth, expandedWidth, fraction)

    fun slot(index: Int): HorizontalTabSlot {
        val current = span(width, index)
        fun relativeSpan(trackWidth: Int) = span(trackWidth, index).let { it.copy(start = it.start - current.start) }
        return HorizontalTabSlot(
            width = current.width,
            compact = relativeSpan(compactWidth),
            expanded = relativeSpan(expandedWidth),
        )
    }

    /** The span of tab [index] when the whole track is [trackWidth] wide, from the available area's leading edge. */
    private fun span(trackWidth: Int, index: Int): TabSpan {
        val trackStart = centeredLeadingOffset(availableWidth - trackWidth, direction)
        val start = tabBoundary(trackWidth, index)
        return TabSpan(start = trackStart + inset + start, width = tabBoundary(trackWidth, index + 1) - start)
    }

    /** Offset of the boundary before tab [index], measured from the track's inner leading edge. */
    private fun tabBoundary(trackWidth: Int, index: Int): Int {
        val innerWidth = (trackWidth - 2 * inset).coerceAtLeast(0)
        return (innerWidth * (index.toFloat() / count)).roundToInt()
    }
}

/**
 * One tab of a [HorizontalTabTrack] at the current fraction. [compact] and [expanded] are where the
 * same tab sits at fraction 0 and 1, relative to its current leading edge.
 */
internal data class HorizontalTabSlot(
    val width: Int,
    val compact: TabSpan,
    val expanded: TabSpan,
) {
    /**
     * Leading x of content that is centered in [compact] at fraction 0 and in [expanded] at
     * fraction 1. Interpolating between the two fixed endpoints keeps the content on a straight path.
     */
    fun contentStart(compactContentWidth: Int, expandedContentWidth: Int, fraction: Float, direction: LayoutDirection): Int =
        lerp(
            compact.centeredStart(compactContentWidth, direction),
            expanded.centeredStart(expandedContentWidth, direction),
            fraction,
        )
}

internal data class TabSpan(val start: Int, val width: Int) {
    fun centeredStart(contentWidth: Int, direction: LayoutDirection): Int =
        start + centeredLeadingOffset(width - contentWidth, direction)
}

/**
 * Leading offset that centers content in [freeWidth] of spare room. It matches `Alignment.Center`,
 * which rounds the physical left margin up, so the logical margin in RTL rounds down.
 */
private fun centeredLeadingOffset(freeWidth: Int, direction: LayoutDirection): Int =
    if (direction == LayoutDirection.Ltr) (freeWidth + 1) / 2 else freeWidth / 2
