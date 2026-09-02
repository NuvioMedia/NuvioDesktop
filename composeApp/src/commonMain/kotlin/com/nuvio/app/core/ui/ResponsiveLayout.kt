package com.nuvio.app.core.ui

internal const val StandardDesktopViewportAspectRatio = 16f / 9f
internal const val WideDesktopViewportAspectRatio = 24f / 9f
internal const val StreamResultsWideArtworkAspectRatio = 26f / 9f
internal const val UltrawideDesktopViewportAspectRatio = 32f / 9f
internal const val DesktopBackdropVerticalBias = -0.6f

internal fun expandingWideArtworkWidthDp(
    widthDp: Float,
    heightDp: Float,
    expansionBreakpointAspectRatio: Float = WideDesktopViewportAspectRatio,
): Float =
    (
        heightDp * StandardDesktopViewportAspectRatio +
            (widthDp - heightDp * expansionBreakpointAspectRatio).coerceAtLeast(0f)
        ).coerceAtMost(widthDp)

internal fun ultrawideViewportProgress(
    widthDp: Float,
    heightDp: Float?,
): Float {
    if (heightDp == null || heightDp <= 0f) return 0f

    val aspectRatio = widthDp / heightDp
    return (
        (aspectRatio - StandardDesktopViewportAspectRatio) /
            (UltrawideDesktopViewportAspectRatio - StandardDesktopViewportAspectRatio)
        ).coerceIn(0f, 1f)
}
