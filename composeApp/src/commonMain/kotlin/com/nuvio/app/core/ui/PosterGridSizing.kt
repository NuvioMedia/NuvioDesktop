package com.nuvio.app.core.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.isDesktop
import kotlin.math.min
import kotlin.math.roundToInt

private const val PosterGridMinimumColumns = 3
private const val PosterGridSpacingDp = 12f
private const val PosterGridHorizontalPaddingDp = 16f

internal fun posterGridColumnCountForWidth(screenWidth: Dp): Int =
    when {
        screenWidth >= 1400.dp -> 7
        screenWidth >= 1200.dp -> 6
        screenWidth >= 1000.dp -> 5
        screenWidth >= 840.dp -> 4
        else -> PosterGridMinimumColumns
    }

internal fun posterGridColumnCountForCatalogWidth(
    screenWidth: Dp,
    basePosterWidthDp: Int,
    useDesktopSizing: Boolean = isDesktop,
): Int {
    val posterWidthDp = catalogPosterBaseWidthDp(
        basePosterWidthDp = basePosterWidthDp,
        useDesktopSizing = useDesktopSizing,
    ).toFloat()
    val availableWidthDp =
        (screenWidth.value - PosterGridHorizontalPaddingDp * 2f).coerceAtLeast(0f)

    return (
        (availableWidthDp + PosterGridSpacingDp) /
            (posterWidthDp + PosterGridSpacingDp)
        ).toInt().coerceAtLeast(PosterGridMinimumColumns)
}

internal fun posterGridColumnCountForViewport(
    screenWidth: Dp,
    screenHeight: Dp,
    basePosterWidthDp: Int,
    useDesktopSizing: Boolean = isDesktop,
): Int {
    val widthBreakpointColumns = posterGridColumnCountForWidth(screenWidth)
    val ultrawideProgress = ultrawideViewportProgress(
        widthDp = screenWidth.value,
        heightDp = screenHeight.value,
    )
    if (!useDesktopSizing || ultrawideProgress <= 0f) return widthBreakpointColumns

    val referenceWidthDp = min(
        screenWidth.value,
        screenHeight.value * StandardDesktopViewportAspectRatio,
    )
    val referenceColumns = posterGridColumnCountForWidth(referenceWidthDp.dp)
    val referenceAvailableWidthDp =
        (referenceWidthDp - PosterGridHorizontalPaddingDp * 2f).coerceAtLeast(0f)
    val referencePosterWidthDp = (
        referenceAvailableWidthDp - PosterGridSpacingDp * (referenceColumns - 1)
        ) / referenceColumns
    val sharedPosterWidthDp = catalogPosterBaseWidthDp(
        basePosterWidthDp = basePosterWidthDp,
        useDesktopSizing = true,
    ).toFloat()
    val targetPosterWidthDp = referencePosterWidthDp +
        (sharedPosterWidthDp - referencePosterWidthDp) * ultrawideProgress
    val availableWidthDp =
        (screenWidth.value - PosterGridHorizontalPaddingDp * 2f).coerceAtLeast(0f)
    val responsiveColumns = (
        (availableWidthDp + PosterGridSpacingDp) /
            (targetPosterWidthDp + PosterGridSpacingDp)
        ).roundToInt().coerceAtLeast(PosterGridMinimumColumns)

    return maxOf(widthBreakpointColumns, responsiveColumns)
}
