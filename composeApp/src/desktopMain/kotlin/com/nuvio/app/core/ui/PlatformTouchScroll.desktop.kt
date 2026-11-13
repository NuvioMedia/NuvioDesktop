package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier
import com.nuvio.app.windowsTouchScroll

internal actual fun Modifier.platformTouchScroll(): Modifier = windowsTouchScroll()
