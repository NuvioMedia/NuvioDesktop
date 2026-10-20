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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.jelly.JellyNavigationBar
import com.nuvio.app.features.settings.NavBarStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

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
    val scrollOffset = scrollState?.totalScrollOffset ?: 0f
    val isScrolledAwayFromTop = scrollOffset > if (isSettingsSelected) 70f else 35f
    val isFrosted = isHovered || profileSwitcherOpen ||
        ((isHomeSelected || isSettingsSelected) && isScrolledAwayFromTop)
    val labelFraction by animateFloatAsState(
        targetValue = desktopNavigationLabelFraction(navBarStyle, isHomeSelected, isHeroEnabled, isHovered, profileSwitcherOpen, isScrolledAwayFromTop),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "desktop_nav_labels",
    )
    val surfaceColor by animateColorAsState(
        targetValue = if (isFrosted) Color(0xFF1C1C1E).copy(alpha = 0.30f) else Color(0xFF0F0F11).copy(alpha = 0.20f),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "desktop_nav_surface",
    )
    val sheenAlpha by animateFloatAsState(
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
        modifier = modifier.fillMaxWidth().padding(contentPadding).padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val viewportWidth = windowWidth ?: maxWidth
        val iconSize = when {
            viewportWidth < 800.dp -> 26.dp
            viewportWidth > 1600.dp -> 30.dp
            else -> 28.dp
        }
        val trackWidth = ((iconSize + 20.dp + 64.dp * labelFraction) * items.size + 8.dp).coerceAtMost(maxWidth)
        JellyNavigationBar(
            items = items,
            labelFraction = labelFraction,
            glowStrength = glowStrength,
            horizontalLabels = true,
            iconSize = iconSize,
            modifier = Modifier.width(trackWidth).height(iconSize + 20.dp).hoverable(interactionSource),
        ) {
            Box(
                modifier = Modifier.matchParentSize()
                    .clip(RoundedCornerShape(50))
                    .then(
                        if (isFrosted && hazeState != null) {
                            Modifier.hazeEffect(state = hazeState) { blurRadius = 14.dp }
                        } else {
                            Modifier
                        },
                    )
                    .background(surfaceColor)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.14f * sheenAlpha),
                                Color.White.copy(alpha = 0.03f * sheenAlpha),
                                Color.Transparent,
                            ),
                        ),
                    ),
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
