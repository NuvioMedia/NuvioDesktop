package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.AppIconResource
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.appIconPainter
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.platformTouchScroll
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.anime4k_cd_label
import nuvio.composeapp.generated.resources.anime4k_error_apply
import nuvio.composeapp.generated.resources.anime4k_menu_applied_format
import nuvio.composeapp.generated.resources.anime4k_menu_default_badge
import nuvio.composeapp.generated.resources.anime4k_menu_description
import nuvio.composeapp.generated.resources.anime4k_menu_installing
import nuvio.composeapp.generated.resources.anime4k_menu_not_installed
import nuvio.composeapp.generated.resources.anime4k_menu_source_format
import nuvio.composeapp.generated.resources.settings_playback_anime4k_install
import org.jetbrains.compose.resources.stringResource

internal data class Anime4kControlState(
    val mode: Anime4kMode,
    val active: Boolean,
    val installed: Boolean,
    val installing: Boolean,
    val resolvedMode: Anime4kMode?,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val error: String?,
    val onSelect: (Anime4kMode) -> Unit,
    val onInstall: () -> Unit,
)

private val MenuShape = RoundedCornerShape(20.dp)
private val RowShape = RoundedCornerShape(10.dp)

/**
 * The player's Anime4K button and the preset popup. The popup deliberately stays open after a
 * selection so comparing presets does not require reopening it; clicking the icon or anywhere
 * outside closes it, like the player's other transient menus.
 */
@Composable
internal fun Anime4kPlayerAction(
    state: Anime4kControlState,
    onInteraction: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = {
                expanded = !expanded
                onInteraction()
            },
            modifier = Modifier.size(48.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(if (state.active) 40.dp else 36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (state.active) Color.White.copy(alpha = 0.16f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = appIconPainter(AppIconResource.PlayerAnime4k),
                    contentDescription = stringResource(Res.string.anime4k_cd_label),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .platformTouchScroll()
                .widthIn(min = 320.dp, max = 410.dp),
            offset = DpOffset(0.dp, (-8).dp),
            shape = MenuShape,
            containerColor = tokens.colors.surfacePopover,
            tonalElevation = tokens.elevation.flat,
            shadowElevation = tokens.elevation.overlay,
            border = androidx.compose.foundation.BorderStroke(tokens.borders.thin, tokens.colors.borderDefault.copy(alpha = 0.65f)),
        ) {
            Anime4kMenu(
                state = state,
                onSelect = {
                    state.onSelect(it)
                    onInteraction()
                },
                onInstall = {
                    state.onInstall()
                    onInteraction()
                },
            )
        }
    }
}

@Composable
private fun Anime4kMenu(
    state: Anime4kControlState,
    onSelect: (Anime4kMode) -> Unit,
    onInstall: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .widthIn(min = 320.dp, max = 410.dp)
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Text(
            text = "ANIME4K",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = tokens.colors.textMuted,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.anime4k_menu_description),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
            color = tokens.colors.textMuted,
        )
        Spacer(Modifier.height(12.dp))

        Anime4kMode.entries.forEach { mode ->
            Anime4kMenuRow(
                mode = mode,
                selected = state.mode == mode,
                isDefault = mode == Anime4kMode.default,
                onClick = { onSelect(mode) },
            )
        }

        val resolved = state.resolvedMode
        if (state.mode == Anime4kMode.Auto && (resolved != null || state.sourceWidth > 0)) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    Res.string.anime4k_menu_applied_format,
                    stringResource((resolved ?: Anime4kMode.Off).labelRes),
                ) +
                    if (state.sourceWidth > 0 && state.sourceHeight > 0) {
                        " · " + stringResource(
                            Res.string.anime4k_menu_source_format,
                            state.sourceWidth,
                            state.sourceHeight,
                        )
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = tokens.colors.textMuted.copy(alpha = 0.85f),
            )
        }

        if (!state.installed) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RowShape)
                    .background(tokens.colors.accent.copy(alpha = 0.12f))
                    .clickable(enabled = !state.installing, onClick = onInstall)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.installing) {
                        stringResource(Res.string.anime4k_menu_installing)
                    } else {
                        stringResource(Res.string.anime4k_menu_not_installed) + " · " +
                            stringResource(Res.string.settings_playback_anime4k_install)
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    color = tokens.colors.accent,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (error.startsWith("not-installed:")) {
                    stringResource(Res.string.anime4k_menu_not_installed)
                } else {
                    stringResource(Res.string.anime4k_error_apply, error)
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = tokens.colors.danger,
            )
        }
    }
}

@Composable
private fun Anime4kMenuRow(
    mode: Anime4kMode,
    selected: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RowShape)
            .background(if (selected) tokens.colors.textPrimary.copy(alpha = 0.06f) else Color.Transparent)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) tokens.colors.borderDefault.copy(alpha = 0.55f) else Color.Transparent,
                shape = RowShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(mode.labelRes),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = if (selected) tokens.colors.textPrimary else tokens.colors.textSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (isDefault) {
            Text(
                text = stringResource(Res.string.anime4k_menu_default_badge),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = tokens.colors.textMuted.copy(alpha = 0.75f),
            )
        }
    }
}
