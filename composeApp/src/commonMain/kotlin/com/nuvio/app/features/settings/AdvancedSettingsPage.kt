package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.controller.ControllerGlyphPreference
import com.nuvio.app.controller.ControllerSettings
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache_done
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache_subtitle
import nuvio.composeapp.generated.resources.settings_advanced_discord_rich_presence
import nuvio.composeapp.generated.resources.settings_advanced_discord_rich_presence_description
import nuvio.composeapp.generated.resources.settings_advanced_opengl_renderer
import nuvio.composeapp.generated.resources.settings_advanced_opengl_renderer_description
import nuvio.composeapp.generated.resources.settings_advanced_opengl_renderer_external_description
import nuvio.composeapp.generated.resources.settings_advanced_remember_last_profile
import nuvio.composeapp.generated.resources.settings_advanced_remember_last_profile_description
import nuvio.composeapp.generated.resources.settings_advanced_section_cache
import nuvio.composeapp.generated.resources.settings_advanced_section_controller
import nuvio.composeapp.generated.resources.settings_advanced_section_diagnostics
import nuvio.composeapp.generated.resources.settings_advanced_section_discord
import nuvio.composeapp.generated.resources.settings_advanced_section_startup
import nuvio.composeapp.generated.resources.settings_advanced_section_windows_graphics
import nuvio.composeapp.generated.resources.settings_advanced_sentry_reports
import nuvio.composeapp.generated.resources.settings_advanced_sentry_reports_subtitle
import nuvio.composeapp.generated.resources.settings_advanced_sentry_reports_subtitle_desktop
import nuvio.composeapp.generated.resources.settings_controller_glyph_auto
import nuvio.composeapp.generated.resources.settings_controller_glyph_nintendo
import nuvio.composeapp.generated.resources.settings_controller_glyph_playstation
import nuvio.composeapp.generated.resources.settings_controller_glyph_steam_deck
import nuvio.composeapp.generated.resources.settings_controller_glyph_xbox
import nuvio.composeapp.generated.resources.settings_controller_glyphs
import nuvio.composeapp.generated.resources.settings_controller_glyphs_description
import nuvio.composeapp.generated.resources.settings_controller_ignore_background
import nuvio.composeapp.generated.resources.settings_controller_ignore_background_description
import nuvio.composeapp.generated.resources.settings_controller_steam_keyboard
import nuvio.composeapp.generated.resources.settings_controller_steam_keyboard_description
import nuvio.composeapp.generated.resources.sentry_disable_dialog_subtitle
import nuvio.composeapp.generated.resources.sentry_disable_dialog_subtitle_desktop
import nuvio.composeapp.generated.resources.sentry_disable_dialog_title
import nuvio.composeapp.generated.resources.sentry_enable_dialog_subtitle
import nuvio.composeapp.generated.resources.sentry_enable_dialog_subtitle_desktop
import nuvio.composeapp.generated.resources.sentry_enable_dialog_title
import nuvio.composeapp.generated.resources.sentry_help_body
import nuvio.composeapp.generated.resources.sentry_help_body_desktop
import nuvio.composeapp.generated.resources.sentry_help_title
import nuvio.composeapp.generated.resources.sentry_keep_enabled
import nuvio.composeapp.generated.resources.sentry_not_sent_body
import nuvio.composeapp.generated.resources.sentry_not_sent_title
import nuvio.composeapp.generated.resources.sentry_sent_body
import nuvio.composeapp.generated.resources.sentry_sent_body_desktop
import nuvio.composeapp.generated.resources.sentry_sent_title
import nuvio.composeapp.generated.resources.sentry_turn_off
import nuvio.composeapp.generated.resources.sentry_turn_on
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.advancedSettingsContent(
    isTablet: Boolean,
    rememberLastProfileEnabled: Boolean,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_advanced_section_startup),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_advanced_remember_last_profile),
                    description = stringResource(Res.string.settings_advanced_remember_last_profile_description),
                    checked = rememberLastProfileEnabled,
                    isTablet = isTablet,
                    onCheckedChange = ProfileRepository::setRememberLastProfileEnabled,
                )
            }
        }
    }
    if (DesktopRendererSettings.isSupported) {
        item {
            val externallyControlled = remember { DesktopRendererSettings.isExternallyControlled }
            var useOpenGl by remember { mutableStateOf(DesktopRendererSettings.useOpenGl) }

            SettingsSection(
                title = stringResource(Res.string.settings_advanced_section_windows_graphics),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_advanced_opengl_renderer),
                        description = stringResource(
                            if (externallyControlled) {
                                Res.string.settings_advanced_opengl_renderer_external_description
                            } else {
                                Res.string.settings_advanced_opengl_renderer_description
                            },
                        ),
                        checked = useOpenGl,
                        enabled = !externallyControlled,
                        isTablet = isTablet,
                        onCheckedChange = { enabled ->
                            DesktopRendererSettings.setUseOpenGl(enabled)
                            useOpenGl = enabled
                        },
                    )
                }
            }
        }
    }
    if (SentrySettingsRepository.isSupported) {
        item {
            val sentryEnabledFlow = remember {
                SentrySettingsRepository.ensureLoaded()
                SentrySettingsRepository.enabled
            }
            val sentryEnabled by sentryEnabledFlow.collectAsStateWithLifecycle()
            var showSentryDialog by rememberSaveable { mutableStateOf(false) }

            SettingsSection(
                title = stringResource(Res.string.settings_advanced_section_diagnostics),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_advanced_sentry_reports),
                        description = stringResource(
                            if (SentrySettingsPlatform.usesDesktopCopy) {
                                Res.string.settings_advanced_sentry_reports_subtitle_desktop
                            } else {
                                Res.string.settings_advanced_sentry_reports_subtitle
                            },
                        ),
                        checked = sentryEnabled,
                        isTablet = isTablet,
                        onCheckedChange = { showSentryDialog = true },
                    )
                }
            }

            if (showSentryDialog) {
                SentrySettingsDialog(
                    enabled = sentryEnabled,
                    onConfirm = {
                        SentrySettingsRepository.setEnabled(!sentryEnabled)
                    },
                    onDismiss = {
                        showSentryDialog = false
                    },
                )
            }
        }
    }
    if (DiscordRichPresenceRepository.isSupported) {
        item {
            val discordEnabledFlow = remember {
                DiscordRichPresenceRepository.ensureLoaded()
                DiscordRichPresenceRepository.enabled
            }
            val discordEnabled by discordEnabledFlow.collectAsStateWithLifecycle()

            SettingsSection(
                title = stringResource(Res.string.settings_advanced_section_discord),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_advanced_discord_rich_presence),
                        description = stringResource(Res.string.settings_advanced_discord_rich_presence_description),
                        checked = discordEnabled,
                        isTablet = isTablet,
                        onCheckedChange = DiscordRichPresenceRepository::setEnabled,
                    )
                }
            }
        }
    }
    if (ControllerSettings.isSupported) {
        item {
            val glyphPref by ControllerSettings.glyphPreference.collectAsStateWithLifecycle()
            val environment by ControllerSettings.environment.collectAsStateWithLifecycle()
            val steamKeyboardEnabled by ControllerSettings.steamKeyboardEnabled.collectAsStateWithLifecycle()
            val ignoreBackground by ControllerSettings.ignoreBackgroundInput.collectAsStateWithLifecycle()

            SettingsSection(
                title = stringResource(Res.string.settings_advanced_section_controller),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsChipRow(
                        title = stringResource(Res.string.settings_controller_glyphs),
                        description = stringResource(Res.string.settings_controller_glyphs_description),
                        isTablet = isTablet,
                    ) {
                        ControllerGlyphPreference.entries.forEach { option ->
                            val labelRes = when (option) {
                                ControllerGlyphPreference.Auto -> Res.string.settings_controller_glyph_auto
                                ControllerGlyphPreference.Xbox -> Res.string.settings_controller_glyph_xbox
                                ControllerGlyphPreference.PlayStation -> Res.string.settings_controller_glyph_playstation
                                ControllerGlyphPreference.Nintendo -> Res.string.settings_controller_glyph_nintendo
                                ControllerGlyphPreference.SteamDeck -> Res.string.settings_controller_glyph_steam_deck
                            }
                            Chip(
                                label = stringResource(labelRes),
                                selected = glyphPref == option,
                                onClick = { ControllerSettings.setGlyphPreference(option) },
                            )
                        }
                    }
                    if (environment.steamKeyboardAvailable) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_controller_steam_keyboard),
                            description = stringResource(Res.string.settings_controller_steam_keyboard_description),
                            checked = steamKeyboardEnabled,
                            isTablet = isTablet,
                            onCheckedChange = ControllerSettings::setSteamKeyboardEnabled,
                        )
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_controller_ignore_background),
                        description = stringResource(Res.string.settings_controller_ignore_background_description),
                        checked = ignoreBackground,
                        isTablet = isTablet,
                        onCheckedChange = ControllerSettings::setIgnoreBackgroundInput,
                    )
                }
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_advanced_section_cache),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                val scope = rememberCoroutineScope()
                var cleared by rememberSaveable { mutableStateOf(false) }
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_advanced_clear_cw_cache),
                    description = if (cleared) {
                        stringResource(Res.string.settings_advanced_clear_cw_cache_done)
                    } else {
                        stringResource(Res.string.settings_advanced_clear_cw_cache_subtitle)
                    },
                    isTablet = isTablet,
                    onClick = {
                        if (!cleared) {
                            ContinueWatchingEnrichmentCache.clearAll(ProfileRepository.activeProfileId)
                            cleared = true
                            scope.launch {
                                WatchProgressRepository.clearLocalAndForceSnapshotRefreshFromServer(
                                    ProfileRepository.activeProfileId,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SentrySettingsDialog(
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(
                        if (enabled) {
                            Res.string.sentry_disable_dialog_title
                        } else {
                            Res.string.sentry_enable_dialog_title
                        },
                    ),
    ) {
        Text(
            text = stringResource(
                when {
                    enabled && SentrySettingsPlatform.usesDesktopCopy -> {
                        Res.string.sentry_disable_dialog_subtitle_desktop
                    }
                    enabled -> Res.string.sentry_disable_dialog_subtitle
                    SentrySettingsPlatform.usesDesktopCopy -> {
                        Res.string.sentry_enable_dialog_subtitle_desktop
                    }
                    else -> Res.string.sentry_enable_dialog_subtitle
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textMuted,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            SentryInfoSection(
                title = stringResource(Res.string.sentry_help_title),
                body = stringResource(
                    if (SentrySettingsPlatform.usesDesktopCopy) {
                        Res.string.sentry_help_body_desktop
                    } else {
                        Res.string.sentry_help_body
                    },
                ),
            )
            SentryInfoSection(
                title = stringResource(Res.string.sentry_sent_title),
                body = stringResource(
                    if (SentrySettingsPlatform.usesDesktopCopy) {
                        Res.string.sentry_sent_body_desktop
                    } else {
                        Res.string.sentry_sent_body
                    },
                ),
            )
            SentryInfoSection(
                title = stringResource(Res.string.sentry_not_sent_title),
                body = stringResource(Res.string.sentry_not_sent_body),
            )
        }
        DialogButtons {
            DialogButton(
                text = stringResource(
                        if (enabled) {
                            Res.string.sentry_keep_enabled
                        } else {
                            Res.string.action_cancel
                        },
                    ),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(
                        if (enabled) {
                            Res.string.sentry_turn_off
                        } else {
                            Res.string.sentry_turn_on
                        },
                    ),
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

@Composable
private fun SentryInfoSection(
    title: String,
    body: String,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
    }
}
