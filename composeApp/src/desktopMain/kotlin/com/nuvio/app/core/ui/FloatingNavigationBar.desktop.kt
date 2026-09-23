package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.settings.NavBarStyle
import dev.chrisbanes.haze.HazeState

internal actual val floatingNavigationGlowSupported: Boolean = true

@Composable
internal actual fun FloatingNavigationBar(
    items: List<FloatingNavigationItem>,
    modifier: Modifier,
    scrollState: NuvioNavBarScrollState?,
    hazeState: HazeState?,
    contentPadding: PaddingValues,
    compactSize: Boolean,
    glowEnabled: Boolean,
) {
    DesktopNavigationBar(
        items = items,
        modifier = modifier,
        scrollState = scrollState,
        hazeState = hazeState,
        contentPadding = contentPadding,
        navBarStyle = if (compactSize || scrollState?.labelVisibility == 0f) NavBarStyle.COMPACT else NavBarStyle.EXPANDED,
        glowEnabled = glowEnabled,
    )
}
