package com.nuvio.app.core.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Horizontal inset for top-level desktop page content.
 *
 * Full-bleed artwork stays outside this inset; headers, controls, shelves, and
 * grids use it so their leading and trailing edges remain aligned.
 */
internal fun desktopPageHorizontalPaddingForWidth(maxWidthDp: Float): Dp =
    when {
        maxWidthDp >= 1440f -> 32.dp
        maxWidthDp >= 1024f -> 28.dp
        maxWidthDp >= 768f -> 24.dp
        else -> 16.dp
    }
