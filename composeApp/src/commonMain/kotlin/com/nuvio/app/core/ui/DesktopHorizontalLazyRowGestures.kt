package com.nuvio.app.core.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier

expect fun Modifier.desktopHorizontalLazyRowGestures(listState: LazyListState): Modifier
