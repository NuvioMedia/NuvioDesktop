package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_advanced_section_network
import nuvio.composeapp.generated.resources.settings_proxy_active
import nuvio.composeapp.generated.resources.settings_proxy_address_invalid
import nuvio.composeapp.generated.resources.settings_proxy_address_placeholder
import nuvio.composeapp.generated.resources.settings_proxy_bypass_description
import nuvio.composeapp.generated.resources.settings_proxy_bypass_label
import nuvio.composeapp.generated.resources.settings_proxy_bypass_placeholder
import nuvio.composeapp.generated.resources.settings_proxy_description
import nuvio.composeapp.generated.resources.settings_proxy_detect
import nuvio.composeapp.generated.resources.settings_proxy_direct
import nuvio.composeapp.generated.resources.settings_proxy_mode_automatic
import nuvio.composeapp.generated.resources.settings_proxy_mode_automatic_description
import nuvio.composeapp.generated.resources.settings_proxy_mode_manual
import nuvio.composeapp.generated.resources.settings_proxy_mode_manual_description
import nuvio.composeapp.generated.resources.settings_proxy_mode_off
import nuvio.composeapp.generated.resources.settings_proxy_mode_off_description
import nuvio.composeapp.generated.resources.settings_proxy_not_detected
import nuvio.composeapp.generated.resources.settings_proxy_restart_hint
import nuvio.composeapp.generated.resources.settings_proxy_save
import nuvio.composeapp.generated.resources.settings_proxy_saved
import org.jetbrains.compose.resources.stringResource

/**
 * Proxy settings for the desktop app.
 *
 * Automatic detection is the default, because most people who need a proxy already have one
 * configured in their shell or their desktop environment and the app simply wasn't reading it.
 * Manual is there for when detection can't see it; Direct turns every proxy off.
 */
internal fun LazyListScope.proxySettingsContent(isTablet: Boolean) {
    item {
        ProxySettingsSection(isTablet = isTablet)
    }
}

@Composable
private fun ProxySettingsSection(isTablet: Boolean) {
    var settings by remember { mutableStateOf(DesktopProxy.current()) }
    var addressDraft by remember { mutableStateOf(settings.manualEndpoint) }
    var bypassDraft by remember { mutableStateOf(settings.bypass) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val addressValid = settings.mode != ProxyMode.Manual ||
        addressDraft.isBlank() ||
        ProxyEndpoint.parse(addressDraft) != null

    // Strings are read here, in the composition, so the click handlers below can stay plain
    // lambdas: stringResource is @Composable and cannot be called from them.
    val description = stringResource(Res.string.settings_proxy_description)
    val addressPlaceholder = stringResource(Res.string.settings_proxy_address_placeholder)
    val addressInvalid = stringResource(Res.string.settings_proxy_address_invalid)
    val bypassLabel = stringResource(Res.string.settings_proxy_bypass_label)
    val bypassPlaceholder = stringResource(Res.string.settings_proxy_bypass_placeholder)
    val bypassDescription = stringResource(Res.string.settings_proxy_bypass_description)
    val detectLabel = stringResource(Res.string.settings_proxy_detect)
    val saveLabel = stringResource(Res.string.settings_proxy_save)
    val restartHint = stringResource(Res.string.settings_proxy_restart_hint)
    val notDetected = stringResource(Res.string.settings_proxy_not_detected)
    val savedLabel = stringResource(Res.string.settings_proxy_saved)

    SettingsSection(
        title = stringResource(Res.string.settings_advanced_section_network),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_proxy_mode_automatic),
                description = stringResource(Res.string.settings_proxy_mode_automatic_description),
                checked = settings.mode == ProxyMode.Automatic,
                isTablet = isTablet,
                onCheckedChange = {
                    settings = settings.copy(mode = ProxyMode.Automatic)
                    statusMessage = null
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_proxy_mode_manual),
                description = stringResource(Res.string.settings_proxy_mode_manual_description),
                checked = settings.mode == ProxyMode.Manual,
                isTablet = isTablet,
                onCheckedChange = {
                    settings = settings.copy(mode = ProxyMode.Manual)
                    statusMessage = null
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_proxy_mode_off),
                description = stringResource(Res.string.settings_proxy_mode_off_description),
                checked = settings.mode == ProxyMode.Off,
                isTablet = isTablet,
                onCheckedChange = {
                    settings = settings.copy(mode = ProxyMode.Off)
                    statusMessage = null
                },
            )
        }
    }

    SettingsSection(
        title = stringResource(Res.string.settings_proxy_mode_manual),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
                Spacer(modifier = Modifier.height(12.dp))
                NuvioInputField(
                    value = addressDraft,
                    onValueChange = {
                        addressDraft = it
                        statusMessage = null
                    },
                    placeholder = addressPlaceholder,
                )
                if (!addressValid) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = addressInvalid,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = bypassLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(6.dp))
                NuvioInputField(
                    value = bypassDraft,
                    onValueChange = {
                        bypassDraft = it
                        statusMessage = null
                    },
                    placeholder = bypassPlaceholder,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = bypassDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
                Spacer(modifier = Modifier.height(16.dp))
                NuvioPrimaryButton(
                    text = detectLabel,
                    onClick = {
                        val detected = DesktopProxy.detectNow()
                        settings = detected
                        if (detected.bypass.isNotBlank()) bypassDraft = detected.bypass
                        statusMessage = detected.detectedEndpoint.ifBlank { notDetected }
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                NuvioPrimaryButton(
                    text = saveLabel,
                    enabled = addressValid,
                    onClick = {
                        val saved = settings.copy(
                            manualEndpoint = addressDraft.trim(),
                            bypass = bypassDraft.trim(),
                        )
                        DesktopProxy.update(saved)
                        settings = saved
                        statusMessage = savedLabel
                    },
                )
                val active = settings.copy(
                    manualEndpoint = addressDraft.trim(),
                    bypass = bypassDraft.trim(),
                ).activeEndpoint()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (active == null) {
                        stringResource(Res.string.settings_proxy_direct)
                    } else {
                        stringResource(Res.string.settings_proxy_active, active.url)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                )
                statusMessage?.let { message ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.nuvio.colors.textMuted,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = restartHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
            }
        }
    }
}
