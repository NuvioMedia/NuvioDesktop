package com.nuvio.app

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.awt.ComposeWindow

val LocalDesktopWindow = compositionLocalOf<ComposeWindow?> { null }
