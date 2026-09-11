package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.shortcutsSettingsContent(
    isTablet: Boolean,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.compose_settings_shortcuts_section_general),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_back), isTablet) {
                    ShortcutKey(stringResource(Res.string.compose_settings_shortcuts_key_esc))
                    ShortcutOrText()
                    ShortcutMouseKey(MouseButton.Backward, stringResource(Res.string.compose_settings_shortcuts_mouse_backward))
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_options), isTablet) {
                    ShortcutMouseKey(MouseButton.Right, stringResource(Res.string.compose_settings_shortcuts_mouse_right))
                    ShortcutOrText()
                    ShortcutMouseKey(MouseButton.Left, stringResource(Res.string.compose_settings_shortcuts_mouse_hold_left))
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_home), isTablet) {
                    ShortcutKey("1")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_search), isTablet) {
                    ShortcutKey("2")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_library), isTablet) {
                    ShortcutKey("3")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_settings), isTablet) {
                    ShortcutKey("4")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_search_box), isTablet) {
                    ShortcutKey("/")
                    ShortcutOrText()
                    ShortcutKey("0")
                }
            }
        }
    }
    
    item {
        SettingsSection(
            title = stringResource(Res.string.compose_settings_shortcuts_section_player),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_play_pause), isTablet) {
                    ShortcutKey(stringResource(Res.string.compose_settings_shortcuts_key_space))
                    ShortcutOrText()
                    ShortcutKey("K")
                    ShortcutOrText()
                    ShortcutMouseKey(MouseButton.Left, stringResource(Res.string.compose_settings_shortcuts_mouse_left))
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_audio_track), isTablet) {
                    ShortcutKey("B")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_subtitle_toggle), isTablet) {
                    ShortcutKey("V")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_mute), isTablet) {
                    ShortcutKey("M")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_seek), isTablet) {
                    ShortcutIconKey(Icons.Default.KeyboardArrowLeft)
                    ShortcutOrText()
                    ShortcutIconKey(Icons.Default.KeyboardArrowRight)
                    ShortcutOrText()
                    ShortcutMouseKey(MouseButton.Forward, stringResource(Res.string.compose_settings_shortcuts_mouse_forward))
                    ShortcutOrText()
                    ShortcutMouseKey(MouseButton.Backward, stringResource(Res.string.compose_settings_shortcuts_mouse_backward))
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_volume), isTablet) {
                    ShortcutIconKey(Icons.Default.KeyboardArrowUp)
                    ShortcutOrText()
                    ShortcutIconKey(Icons.Default.KeyboardArrowDown)
                    ShortcutOrText()
                    ShortcutMouseKey(MouseButton.ScrollUp, stringResource(Res.string.compose_settings_shortcuts_mouse_scroll_up))
                    ShortcutOrText()
                    ShortcutMouseKey(MouseButton.ScrollDown, stringResource(Res.string.compose_settings_shortcuts_mouse_scroll_down))
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_fullscreen), isTablet) {
                    ShortcutKey("F11")
                    ShortcutOrText()
                    ShortcutKey("F")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_skip_intro), isTablet) {
                    ShortcutKey(stringResource(Res.string.compose_settings_shortcuts_key_enter))
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_audio_selector), isTablet) {
                    ShortcutKey("A")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_subtitle_selector), isTablet) {
                    ShortcutKey("S")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_source_list), isTablet) {
                    ShortcutKey("Q")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_episode_list), isTablet) {
                    ShortcutKey("E")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_focus_options), isTablet) {
                    ShortcutIconKey(Icons.Default.KeyboardArrowUp)
                    ShortcutOrText()
                    ShortcutIconKey(Icons.Default.KeyboardArrowDown)
                    ShortcutOrText()
                    ShortcutIconKey(Icons.Default.KeyboardArrowLeft)
                    ShortcutOrText()
                    ShortcutIconKey(Icons.Default.KeyboardArrowRight)
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_next_episode), isTablet) {
                    ShortcutKey(stringResource(Res.string.compose_settings_shortcuts_key_shift))
                    ShortcutPlusText()
                    ShortcutKey("N")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_speed_hold), isTablet) {
                    ShortcutKey(stringResource(Res.string.compose_settings_shortcuts_key_hold_space))
                    ShortcutOrText()
                    ShortcutMouseKey(MouseButton.Left, stringResource(Res.string.compose_settings_shortcuts_mouse_hold_left))
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_speed_panel), isTablet) {
                    ShortcutKey("`")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_speed_control), isTablet) {
                    ShortcutKey(stringResource(Res.string.compose_settings_shortcuts_key_shift))
                    ShortcutPlusText()
                    ShortcutKey("<")
                    ShortcutOrText()
                    ShortcutKey(stringResource(Res.string.compose_settings_shortcuts_key_shift))
                    ShortcutPlusText()
                    ShortcutKey(">")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_speed_reset), isTablet) {
                    ShortcutKey("/")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_subtitle_delay), isTablet) {
                    ShortcutKey("G")
                    ShortcutOrText()
                    ShortcutKey("H")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_sub_opacity_toggle), isTablet) {
                    ShortcutKey("O")
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShortcutRow(stringResource(Res.string.compose_settings_shortcuts_row_sub_opacity_adjust), isTablet) {
                    ShortcutKey("I")
                    ShortcutOrText()
                    ShortcutKey("P")
                }
            }
        }
    }
}

@Composable
private fun ShortcutRow(
    title: String,
    isTablet: Boolean,
    keys: @Composable RowScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    val horizontalPadding = if (isTablet) 20.dp else 16.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            keys()
        }
    }
}

@Composable
private fun ShortcutKey(label: String) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .background(
                color = tokens.colors.surfaceCard,
                shape = RoundedCornerShape(6.dp)
            )
            .border(
                width = 1.dp,
                color = tokens.colors.borderSubtle,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ShortcutIconKey(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .background(
                color = tokens.colors.surfaceCard,
                shape = RoundedCornerShape(6.dp)
            )
            .border(
                width = 1.dp,
                color = tokens.colors.borderSubtle,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tokens.colors.textPrimary,
            modifier = Modifier.size(18.dp)
        )
    }
}

enum class MouseButton { Left, Right, Middle, ScrollUp, ScrollDown, Forward, Backward, None }

@Composable
private fun ShortcutMouseKey(button: MouseButton, label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.nuvio.colors.borderSubtle,
                shape = RoundedCornerShape(6.dp)
            )
            .background(
                color = MaterialTheme.nuvio.colors.surfaceCard,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val iconRes = when (button) {
                MouseButton.Left -> Res.drawable.mouse_left_button_16
                MouseButton.Right -> Res.drawable.mouse_right_button_16
                MouseButton.Middle, MouseButton.ScrollUp, MouseButton.ScrollDown -> Res.drawable.mouse_middle_button_16
                MouseButton.Forward -> Res.drawable.mouse_m4_button_16
                MouseButton.Backward -> Res.drawable.mouse_m5_button_16
                MouseButton.None -> null
            }
            
            if (iconRes != null) {
                Icon(
                    painter = org.jetbrains.compose.resources.painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.nuvio.colors.textPrimary,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Box(modifier = Modifier.size(16.dp))
            }
            
            Text(
                text = label,
                color = MaterialTheme.nuvio.colors.textPrimary,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ShortcutOrText() {
    Text(
        text = stringResource(Res.string.compose_settings_shortcuts_or),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.nuvio.colors.textSecondary,
        modifier = Modifier.padding(horizontal = 2.dp)
    )
}

@Composable
private fun ShortcutPlusText() {
    Text(
        text = "+",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.nuvio.colors.textSecondary,
        modifier = Modifier.padding(horizontal = 2.dp)
    )
}
