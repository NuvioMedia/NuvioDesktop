package com.nuvio.app.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
internal expect fun NuvioDesktopVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
)

@Composable
internal expect fun NuvioDesktopVerticalScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
)

@Composable
internal expect fun NuvioDesktopVerticalScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
)
