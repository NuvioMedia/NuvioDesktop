package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_addons
import nuvio.composeapp.generated.resources.compose_settings_page_plugins
import nuvio.composeapp.generated.resources.discover_hide_watched
import nuvio.composeapp.generated.resources.discover_hide_watched_sub
import nuvio.composeapp.generated.resources.settings_content_discovery_addons_description
import nuvio.composeapp.generated.resources.settings_content_discovery_addons_description_appstore
import nuvio.composeapp.generated.resources.settings_content_discovery_plugins_description
import nuvio.composeapp.generated.resources.settings_content_discovery_section_discover
import nuvio.composeapp.generated.resources.settings_content_discovery_section_sources
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.contentDiscoveryContent(
    isTablet: Boolean,
    showPluginsEntry: Boolean,
    hideWatchedInDiscover: Boolean,
    onAddonsClick: () -> Unit,
    onPluginsClick: () -> Unit,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_content_discovery_section_discover),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.discover_hide_watched),
                    description = stringResource(Res.string.discover_hide_watched_sub),
                    checked = hideWatchedInDiscover,
                    isTablet = isTablet,
                    onCheckedChange = HomeCatalogSettingsRepository::setHideWatchedInDiscover,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_content_discovery_section_sources),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_addons),
                    description = stringResource(
                        if (AppFeaturePolicy.personalMediaAddonCopyEnabled) {
                            Res.string.settings_content_discovery_addons_description_appstore
                        } else {
                            Res.string.settings_content_discovery_addons_description
                        },
                    ),
                    isTablet = isTablet,
                    onClick = onAddonsClick,
                )
                if (showPluginsEntry) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_plugins),
                        description = stringResource(Res.string.settings_content_discovery_plugins_description),
                        isTablet = isTablet,
                        onClick = onPluginsClick,
                    )
                }
            }
        }
    }
}
