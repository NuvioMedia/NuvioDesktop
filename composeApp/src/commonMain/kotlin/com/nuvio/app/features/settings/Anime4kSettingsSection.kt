package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.AppIconResource
import com.nuvio.app.core.ui.appIconPainter
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.Anime4kInstallStatus
import com.nuvio.app.features.player.Anime4kShaderInstaller
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_anime4k_auto_note
import nuvio.composeapp.generated.resources.settings_playback_anime4k_desc_error
import nuvio.composeapp.generated.resources.settings_playback_anime4k_desc_installed
import nuvio.composeapp.generated.resources.settings_playback_anime4k_desc_installing
import nuvio.composeapp.generated.resources.settings_playback_anime4k_desc_invalid
import nuvio.composeapp.generated.resources.settings_playback_anime4k_desc_not_installed
import nuvio.composeapp.generated.resources.settings_playback_anime4k_desc_unavailable
import nuvio.composeapp.generated.resources.settings_playback_anime4k_install
import nuvio.composeapp.generated.resources.settings_playback_anime4k_install_failed
import nuvio.composeapp.generated.resources.settings_playback_anime4k_install_path
import nuvio.composeapp.generated.resources.settings_playback_anime4k_install_progress
import nuvio.composeapp.generated.resources.settings_playback_anime4k_installed_status
import nuvio.composeapp.generated.resources.settings_playback_anime4k_invalid_status
import nuvio.composeapp.generated.resources.settings_playback_anime4k_not_installed_status
import nuvio.composeapp.generated.resources.settings_playback_anime4k_reinstall
import nuvio.composeapp.generated.resources.settings_playback_anime4k_section
import nuvio.composeapp.generated.resources.settings_playback_anime4k_title
import nuvio.composeapp.generated.resources.settings_playback_anime4k_unavailable_status
import nuvio.composeapp.generated.resources.settings_playback_anime4k_update
import nuvio.composeapp.generated.resources.settings_playback_anime4k_verify
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun Anime4kSettingsSection(isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    val install by Anime4kShaderInstaller.state.collectAsState()
    LaunchedEffect(Unit) { Anime4kShaderInstaller.refresh() }

    val statusText = when (install.status) {
        Anime4kInstallStatus.Installed -> stringResource(
            Res.string.settings_playback_anime4k_installed_status,
            install.version,
        )
        Anime4kInstallStatus.NotInstalled -> stringResource(Res.string.settings_playback_anime4k_not_installed_status)
        Anime4kInstallStatus.Invalid -> stringResource(Res.string.settings_playback_anime4k_invalid_status)
        Anime4kInstallStatus.Installing -> stringResource(
            Res.string.settings_playback_anime4k_install_progress,
            (install.progress * 100f).toInt().coerceIn(0, 100),
        )
        Anime4kInstallStatus.Error -> stringResource(Res.string.settings_playback_anime4k_invalid_status)
        Anime4kInstallStatus.Unavailable -> stringResource(Res.string.settings_playback_anime4k_unavailable_status)
    }
    val descriptionText = when (install.status) {
        Anime4kInstallStatus.Installed -> stringResource(Res.string.settings_playback_anime4k_desc_installed)
        Anime4kInstallStatus.NotInstalled -> stringResource(Res.string.settings_playback_anime4k_desc_not_installed)
        Anime4kInstallStatus.Invalid -> stringResource(Res.string.settings_playback_anime4k_desc_invalid)
        Anime4kInstallStatus.Installing -> stringResource(Res.string.settings_playback_anime4k_desc_installing)
        Anime4kInstallStatus.Error -> stringResource(Res.string.settings_playback_anime4k_desc_error)
        Anime4kInstallStatus.Unavailable -> stringResource(Res.string.settings_playback_anime4k_desc_unavailable)
    }
    val actionText = when {
        install.isInstalling -> stringResource(
            Res.string.settings_playback_anime4k_install_progress,
            (install.progress * 100f).toInt().coerceIn(0, 100),
        )
        install.status == Anime4kInstallStatus.Installed -> stringResource(Res.string.settings_playback_anime4k_reinstall)
        install.status == Anime4kInstallStatus.Invalid -> stringResource(Res.string.settings_playback_anime4k_update)
        install.status == Anime4kInstallStatus.Error -> stringResource(Res.string.settings_playback_anime4k_reinstall)
        install.status == Anime4kInstallStatus.Unavailable -> stringResource(Res.string.settings_playback_anime4k_verify)
        else -> stringResource(Res.string.settings_playback_anime4k_install)
    }

    SettingsSection(
        title = stringResource(Res.string.settings_playback_anime4k_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    painter = appIconPainter(AppIconResource.PlayerAnime4k),
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier
                        .size(if (isTablet) 44.dp else 38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.colors.accent.copy(alpha = 0.12f))
                        .padding(7.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.settings_playback_anime4k_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = descriptionText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = if (install.status == Anime4kInstallStatus.Installed) {
                            tokens.colors.accent
                        } else {
                            tokens.colors.textSecondary
                        },
                        fontWeight = FontWeight.Medium,
                    )
                    if (install.installPath.isNotBlank()) {
                        Text(
                            text = stringResource(
                                Res.string.settings_playback_anime4k_install_path,
                                install.installPath,
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = tokens.colors.textMuted.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val canInstall = install.status != Anime4kInstallStatus.Installing &&
                    install.status != Anime4kInstallStatus.Unavailable
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                    color = if (install.isInstalling) tokens.colors.textMuted else tokens.colors.accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (install.isInstalling) {
                                tokens.colors.surface
                            } else {
                                tokens.colors.accent.copy(alpha = 0.10f)
                            },
                        )
                        .clickable(enabled = canInstall) {
                            Anime4kShaderInstaller.install(
                                force = install.status == Anime4kInstallStatus.Installed ||
                                    install.status == Anime4kInstallStatus.Invalid ||
                                    install.status == Anime4kInstallStatus.Error,
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (install.isInstalling) {
                LinearProgressIndicator(
                    progress = { install.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isTablet) 20.dp else 16.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = tokens.colors.accent,
                    trackColor = tokens.colors.borderSubtle,
                )
                Spacer(Modifier.height(12.dp))
            }
            if (install.status == Anime4kInstallStatus.Error) {
                Text(
                    text = install.error?.let {
                        stringResource(Res.string.settings_playback_anime4k_install_failed, it)
                    } ?: stringResource(Res.string.settings_playback_anime4k_desc_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                    modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            SettingsGroupDivider(isTablet = isTablet)
            Text(
                text = stringResource(Res.string.settings_playback_anime4k_auto_note),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 12.dp),
            )
        }
    }
}                    
