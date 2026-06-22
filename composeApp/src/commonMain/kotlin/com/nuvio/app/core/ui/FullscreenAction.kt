package com.nuvio.app.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_exit_fullscreen
import nuvio.composeapp.generated.resources.action_fullscreen
import org.jetbrains.compose.resources.stringResource

internal expect val isFullscreenActionSupported: Boolean

@Composable
internal expect fun isFullscreenActionActive(): Boolean

internal expect fun toggleFullscreenAction()

internal fun fullscreenActionHorizontalInsetForWidth(maxWidthDp: Float): Dp =
    when {
        maxWidthDp >= 1440f -> 32.dp
        maxWidthDp >= 1024f -> 28.dp
        maxWidthDp >= 768f -> 24.dp
        else -> 16.dp
    }

@Composable
internal fun FullscreenActionButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    buttonSize: Dp = 40.dp,
    iconSize: Dp = 24.dp,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    if (!isFullscreenActionSupported) return

    val isActive = isFullscreenActionActive()
    val label = stringResource(
        if (isActive) {
            Res.string.action_exit_fullscreen
        } else {
            Res.string.action_fullscreen
        },
    )

    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = ::toggleFullscreenAction,
            )
            .semantics {
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        PlayerFullscreenIcon(
            isExit = isActive,
            tint = contentColor.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun PlayerFullscreenIcon(
    isExit: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val iconPaths = remember(isExit) {
        if (isExit) {
            PlayerFullscreenPaths(
                viewport = 385.331f,
                paths = PLAYER_FULLSCREEN_EXIT_PATHS.map { PathParser().parsePathString(it).toPath() },
                stroke = false,
            )
        } else {
            PlayerFullscreenPaths(
                viewport = 24f,
                paths = PLAYER_FULLSCREEN_PATHS.map { PathParser().parsePathString(it).toPath() },
                stroke = true,
            )
        }
    }

    Canvas(modifier = modifier) {
        withTransform(
            {
                scale(
                    scaleX = size.width / iconPaths.viewport,
                    scaleY = size.height / iconPaths.viewport,
                    pivot = androidx.compose.ui.geometry.Offset.Zero,
                )
            },
        ) {
            iconPaths.paths.forEach { path ->
                if (iconPaths.stroke) {
                    drawPath(
                        path = path,
                        color = tint,
                        style = Stroke(width = 1.5f, cap = StrokeCap.Round),
                    )
                } else {
                    drawPath(path = path, color = tint)
                }
            }
        }
    }
}

private data class PlayerFullscreenPaths(
    val viewport: Float,
    val paths: List<androidx.compose.ui.graphics.Path>,
    val stroke: Boolean,
)

private val PLAYER_FULLSCREEN_PATHS = listOf(
    "M8 2H4C2.89543 2 2 2.89543 2 4V8",
    "M22 8L22 4C22 2.89543 21.1046 2 20 2H16",
    "M16 22L20 22C21.1046 22 22 21.1046 22 20L22 16",
    "M8 22L4 22C2.89543 22 2 21.1046 2 20V16",
)

private val PLAYER_FULLSCREEN_EXIT_PATHS = listOf(
    "M264.943 156.665h108.273c6.833 0 11.934-5.39 11.934-12.211 0-6.833-5.101-11.85-11.934-11.838h-96.242V36.181c0-6.833-5.197-12.03-12.03-12.03s-12.03 5.197-12.03 12.03v108.273c0 .036.012.06.012.084 0 .036-.012.06-.012.096-.001 6.713 5.316 12.043 12.029 12.031z",
    "M120.291 24.247c-6.821 0-11.838 5.113-11.838 11.934v96.242H12.03c-6.833 0-12.03 5.197-12.03 12.03 0 6.833 5.197 12.03 12.03 12.03h108.273c.036 0 .06-.012.084-.012.036 0 .06.012.096.012 6.713 0 12.03-5.317 12.03-12.03V36.181c.001-6.821-5.389-11.922-12.222-11.934z",
    "M120.387 228.666H12.115C5.282 228.678.181 234.056.181 240.889s5.101 11.85 11.934 11.838h96.242v96.423c0 6.833 5.197 12.03 12.03 12.03s12.03-5.197 12.03-12.03V240.877c0-.036-.012-.06-.012-.084 0-.036.012-.06.012-.096.001-6.714-5.317-12.031-12.03-12.031z",
    "M373.3 228.666H265.028c-.036 0-.06.012-.084.012-.036 0-.06-.012-.096-.012-6.713 0-12.03 5.317-12.03 12.03v108.273c0 6.833 5.39 11.922 12.223 11.934 6.821.012 11.838-5.101 11.838-11.922v-96.242H373.3c6.833 0 12.03-5.197 12.03-12.03s-5.196-12.031-12.03-12.043z",
)
