package com.nuvio.app.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

@Composable
internal actual fun NuvioDesktopVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier,
    backgroundColor: Color?,
) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
        style = nuvioDesktopScrollbarStyle(backgroundColor),
    )
}

@Composable
internal actual fun NuvioDesktopVerticalScrollbar(
    state: LazyGridState,
    modifier: Modifier,
    backgroundColor: Color?,
) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
        style = nuvioDesktopScrollbarStyle(backgroundColor),
    )
}

@Composable
internal actual fun NuvioDesktopVerticalScrollbar(
    state: ScrollState,
    modifier: Modifier,
    backgroundColor: Color?,
) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
        style = nuvioDesktopScrollbarStyle(backgroundColor),
    )
}

@Composable
private fun nuvioDesktopScrollbarStyle(backgroundColor: Color?): ScrollbarStyle {
    val colorScheme = MaterialTheme.colorScheme
    val adaptiveThumbColor = backgroundColor?.let { color ->
        if (color.luminance() > EqualBlackWhiteContrastLuminance) Color.Black else Color.White
    }
    return ScrollbarStyle(
        minimalHeight = 48.dp,
        thickness = 6.dp,
        shape = RoundedCornerShape(100),
        hoverDurationMillis = 180,
        unhoverColor = adaptiveThumbColor?.copy(alpha = 0.72f)
            ?: colorScheme.onSurfaceVariant.copy(alpha = 0.34f),
        hoverColor = adaptiveThumbColor?.copy(alpha = 0.94f)
            ?: colorScheme.primary.copy(alpha = 0.78f),
    )
}

private const val EqualBlackWhiteContrastLuminance = 0.179f
