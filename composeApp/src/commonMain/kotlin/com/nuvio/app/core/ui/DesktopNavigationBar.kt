package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.jelly.JellyNavigationBar
import com.nuvio.app.core.ui.jelly.horizontalTabTrack
import com.nuvio.app.features.settings.NavBarStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

private val DesktopNavPillShape = RoundedCornerShape(50)
private val DesktopNavTopSheenBrush = Brush.verticalGradient(
    listOf(
        Color.White.copy(alpha = 0.14f),
        Color.White.copy(alpha = 0.03f),
        Color.Transparent,
    ),
)

@Composable
internal fun DesktopNavigationBar(
    items: List<FloatingNavigationItem>,
    modifier: Modifier = Modifier,
    scrollState: NuvioNavBarScrollState? = null,
    hazeState: HazeState? = null,
    contentPadding: PaddingValues = PaddingValues(top = 10.dp, bottom = 8.dp),
    navBarStyle: NavBarStyle = NavBarStyle.ADAPTIVE,
    isHeroEnabled: Boolean = false,
    profileSwitcherOpen: Boolean = false,
    windowWidth: Dp? = null,
    glowEnabled: Boolean = true,
) {
    if (items.isEmpty()) return
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isHomeSelected = items.first().selected
    val isSettingsSelected = items.last().selected
    val scrollThreshold = if (isSettingsSelected) 70f else 35f
    val isScrolledAwayFromTop by remember(scrollState, scrollThreshold) {
        derivedStateOf { (scrollState?.totalScrollOffset ?: 0f) > scrollThreshold }
    }
    val isFrosted = isHovered || profileSwitcherOpen ||
        ((isHomeSelected || isSettingsSelected) && isScrolledAwayFromTop)
    val labelFraction by animateFloatAsState(
        targetValue = desktopNavigationLabelFraction(navBarStyle, isHomeSelected, isHeroEnabled, isHovered, profileSwitcherOpen, isScrolledAwayFromTop),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "desktop_nav_labels",
    )
    val surfaceColorState = animateColorAsState(
        targetValue = if (isFrosted) Color(0xFF1C1C1E).copy(alpha = 0.30f) else Color(0xFF0F0F11).copy(alpha = 0.20f),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "desktop_nav_surface",
    )
    val sheenAlphaState = animateFloatAsState(
        targetValue = if (isFrosted) 1f else 0f,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "desktop_nav_sheen",
    )
    val glowStrength by animateFloatAsState(
        targetValue = if (glowEnabled) 1f else 0f,
        animationSpec = tween(420, easing = NuvioTokens.Motion.standard),
        label = "desktop_nav_glow",
    )
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().nuvioTrackTopBarFocus().padding(contentPadding).padding(horizontal = 16.dp),
        // horizontalTabTrack mirrors this centering, including its rounding.
        contentAlignment = Alignment.TopCenter,
    ) {
        val viewportWidth = windowWidth ?: maxWidth
        val iconSize = when {
            viewportWidth < 800.dp -> 26.dp
            viewportWidth > 1600.dp -> 30.dp
            else -> 28.dp
        }
        // Each compact tab is as wide as the bar is tall.
        val tabSize = iconSize + 20.dp
        val density = LocalDensity.current
        val track = density.horizontalTabTrack(
            count = items.size,
            compactTabWidth = tabSize,
            labelWidth = 64.dp,
            availableWidth = constraints.maxWidth,
            fraction = labelFraction,
            direction = LocalLayoutDirection.current,
        )
        JellyNavigationBar(
            items = items,
            labelFraction = labelFraction,
            glowStrength = glowStrength,
            horizontalTrack = track,
            iconSize = iconSize,
            modifier = Modifier.width(with(density) { track.width.toDp() }).height(tabSize).hoverable(interactionSource),
            focusIdForIndex = { index ->
                "top_nav_" + when (index) {
                    0 -> "home"
                    1 -> "search"
                    2 -> "library"
                    3 -> "settings"
                    else -> "item_$index"
                }
            },
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(DesktopNavPillShape)
                    .then(
                        if (isFrosted && hazeState != null) {
                            Modifier.hazeEffect(state = hazeState) { blurRadius = 14.dp }
                        } else {
                            Modifier
                        },
                    )
                    .drawBehind {
                        drawRect(surfaceColorState.value)
                        val sheen = sheenAlphaState.value
                        if (sheen > 0.005f) {
                            drawRect(brush = DesktopNavTopSheenBrush, alpha = sheen)
                        }
                    },
            )
        }
    }
}

internal fun desktopNavigationLabelFraction(
    style: NavBarStyle,
    isHomeSelected: Boolean,
    isHeroEnabled: Boolean,
    isHovered: Boolean,
    profileSwitcherOpen: Boolean,
    isScrolledAwayFromTop: Boolean,
): Float = when (style) {
    NavBarStyle.EXPANDED -> 1f
    NavBarStyle.COMPACT -> 0f
    else -> when {
        isHovered || profileSwitcherOpen -> 1f
        isHomeSelected && isHeroEnabled -> 0f
        isHomeSelected && isScrolledAwayFromTop -> 0f
        else -> 1f
    }
}
