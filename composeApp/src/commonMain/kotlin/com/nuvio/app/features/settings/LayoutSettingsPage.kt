package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.floatingNavigationGlowSupported
import com.nuvio.app.core.ui.labelRes
import com.nuvio.app.isIos
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.isDesktop
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.cd_selected
import nuvio.composeapp.generated.resources.compose_settings_page_hover_preview
import nuvio.composeapp.generated.resources.settings_appearance_desktop_navigation
import nuvio.composeapp.generated.resources.settings_appearance_desktop_navigation_sheet_title
import nuvio.composeapp.generated.resources.settings_appearance_desktop_navigation_top_bar
import nuvio.composeapp.generated.resources.settings_appearance_hover_preview_description
import nuvio.composeapp.generated.resources.settings_appearance_nav_bar_style_sheet_title
import nuvio.composeapp.generated.resources.settings_appearance_sidebar_style
import nuvio.composeapp.generated.resources.settings_appearance_sidebar_style_sheet_title
import nuvio.composeapp.generated.resources.settings_appearance_top_bar_style
import nuvio.composeapp.generated.resources.settings_appearance_top_bar_style_sheet_title
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.collections_header
import nuvio.composeapp.generated.resources.compose_settings_page_continue_watching
import nuvio.composeapp.generated.resources.compose_settings_page_homescreen
import nuvio.composeapp.generated.resources.compose_settings_page_meta_screen
import nuvio.composeapp.generated.resources.compose_settings_page_poster_customization
import nuvio.composeapp.generated.resources.compose_settings_page_streams
import nuvio.composeapp.generated.resources.compose_settings_root_streams_description
import nuvio.composeapp.generated.resources.settings_appearance_continue_watching_description
import nuvio.composeapp.generated.resources.settings_appearance_liquid_glass
import nuvio.composeapp.generated.resources.settings_appearance_liquid_glass_description
import nuvio.composeapp.generated.resources.settings_appearance_nav_bar_style
import nuvio.composeapp.generated.resources.settings_appearance_poster_customization_description
import nuvio.composeapp.generated.resources.settings_appearance_section_detail_page
import nuvio.composeapp.generated.resources.settings_appearance_section_home
import nuvio.composeapp.generated.resources.settings_appearance_section_streams
import nuvio.composeapp.generated.resources.settings_content_discovery_collections_description
import nuvio.composeapp.generated.resources.settings_content_discovery_homescreen_description
import nuvio.composeapp.generated.resources.settings_content_discovery_meta_screen_description
import nuvio.composeapp.generated.resources.settings_nav_bar_glow_off
import nuvio.composeapp.generated.resources.settings_nav_bar_glow_on
import nuvio.composeapp.generated.resources.settings_nav_bar_summary
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.layoutSettingsContent(
    isTablet: Boolean,
    liquidGlassNativeTabBarSupported: Boolean,
    onHomescreenClick: () -> Unit,
    onMetaScreenClick: () -> Unit,
    onStreamsClick: () -> Unit,
    onCollectionsClick: () -> Unit,
    onContinueWatchingClick: () -> Unit,
    onPosterCustomizationClick: () -> Unit,
    onHoverPreviewClick: () -> Unit,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_appearance_section_home),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_homescreen),
                    description = stringResource(Res.string.settings_content_discovery_homescreen_description),
                    isTablet = isTablet,
                    onClick = onHomescreenClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.collections_header),
                    description = stringResource(Res.string.settings_content_discovery_collections_description),
                    isTablet = isTablet,
                    onClick = onCollectionsClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_continue_watching),
                    description = stringResource(Res.string.settings_appearance_continue_watching_description),
                    isTablet = isTablet,
                    onClick = onContinueWatchingClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_poster_customization),
                    description = stringResource(Res.string.settings_appearance_poster_customization_description),
                    isTablet = isTablet,
                    onClick = onPosterCustomizationClick,
                )
                if (isDesktop) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_hover_preview),
                        description = stringResource(Res.string.settings_appearance_hover_preview_description),
                        isTablet = isTablet,
                        onClick = onHoverPreviewClick,
                    )
                }
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_appearance_section_streams),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_streams),
                    description = stringResource(Res.string.compose_settings_root_streams_description),
                    isTablet = isTablet,
                    onClick = onStreamsClick,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_appearance_section_detail_page),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_meta_screen),
                    description = stringResource(Res.string.settings_content_discovery_meta_screen_description),
                    isTablet = isTablet,
                    onClick = onMetaScreenClick,
                )
            }
        }
    }
    if (isDesktop || !isIos || liquidGlassNativeTabBarSupported) {
        item {
            var showNavBarStyleSheet by remember { mutableStateOf(false) }
            var showDesktopNavigationSheet by remember { mutableStateOf(false) }
            val desktopNavigationLayout by remember {
                ThemeSettingsRepository.ensureLoaded()
                ThemeSettingsRepository.desktopNavigationLayout
            }.collectAsStateWithLifecycle()
            val selectedNavBarStyle by ThemeSettingsRepository.navBarStyle.collectAsStateWithLifecycle()
            val glowEnabled by ThemeSettingsRepository.navBarGlowEnabled.collectAsStateWithLifecycle()
            val liquidGlassNativeTabBarEnabled by ThemeSettingsRepository.liquidGlassNativeTabBarEnabled.collectAsStateWithLifecycle()
            val effectiveNavBarStyle = if (isTablet) NavBarStyle.COMPACT else selectedNavBarStyle
            val navBarStyleAvailable = !isIos
            SettingsSection(
                title = stringResource(Res.string.settings_appearance_nav_bar_style),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    if (liquidGlassNativeTabBarSupported) {
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_appearance_liquid_glass),
                            description = stringResource(Res.string.settings_appearance_liquid_glass_description),
                            checked = liquidGlassNativeTabBarEnabled,
                            isTablet = isTablet,
                            onCheckedChange = ThemeSettingsRepository::setLiquidGlassNativeTabBar,
                        )
                    }
                    if (isDesktop) {
                        SettingsNavigationRow(
                            title = stringResource(Res.string.settings_appearance_desktop_navigation),
                            description = if (isTablet) {
                                stringResource(desktopNavigationLayout.labelRes)
                            } else {
                                stringResource(Res.string.settings_appearance_desktop_navigation_top_bar)
                            },
                            isTablet = isTablet,
                            onClick = {
                                if (isTablet) {
                                    showDesktopNavigationSheet = true
                                }
                            },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        val isSidebarActive = isTablet && desktopNavigationLayout == DesktopNavigationLayout.Sidebar
                        SettingsNavigationRow(
                            title = stringResource(
                                if (isSidebarActive) {
                                    Res.string.settings_appearance_sidebar_style
                                } else {
                                    Res.string.settings_appearance_top_bar_style
                                },
                            ),
                            description = stringResource(selectedNavBarStyle.labelRes),
                            isTablet = isTablet,
                            onClick = { showNavBarStyleSheet = true },
                        )
                    } else if (navBarStyleAvailable) {
                        if (liquidGlassNativeTabBarSupported) SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.settings_appearance_nav_bar_style),
                            description = if (floatingNavigationGlowSupported && effectiveNavBarStyle != NavBarStyle.CLASSIC) {
                                stringResource(
                                    Res.string.settings_nav_bar_summary,
                                    stringResource(effectiveNavBarStyle.labelRes),
                                    stringResource(if (glowEnabled) Res.string.settings_nav_bar_glow_on else Res.string.settings_nav_bar_glow_off),
                                )
                            } else {
                                stringResource(effectiveNavBarStyle.labelRes)
                            },
                            isTablet = isTablet,
                            onClick = { showNavBarStyleSheet = true },
                        )
                    }
                }
            }
            if (showDesktopNavigationSheet) {
                DesktopNavigationLayoutBottomSheet(
                    selectedLayout = desktopNavigationLayout,
                    onLayoutSelected = {
                        ThemeSettingsRepository.setDesktopNavigationLayout(it)
                        showDesktopNavigationSheet = false
                    },
                    onDismiss = { showDesktopNavigationSheet = false },
                )
            }
            if (isDesktop && showNavBarStyleSheet) {
                NavBarStyleBottomSheet(
                    selectedStyle = selectedNavBarStyle,
                    isTablet = isTablet,
                    desktopNavigationLayout = desktopNavigationLayout,
                    onStyleSelected = {
                        ThemeSettingsRepository.setNavBarStyle(it)
                        showNavBarStyleSheet = false
                    },
                    onDismiss = { showNavBarStyleSheet = false },
                )
            } else if (navBarStyleAvailable && showNavBarStyleSheet) {
                NavigationBarSettingsSheet(
                    isTablet = isTablet,
                    selectedStyle = effectiveNavBarStyle,
                    onStyleSelected = ThemeSettingsRepository::setNavBarStyle,
                    glowEnabled = glowEnabled,
                    onGlowChanged = ThemeSettingsRepository::setNavBarGlowEnabled,
                    onDismiss = { showNavBarStyleSheet = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopNavigationLayoutBottomSheet(
    selectedLayout: DesktopNavigationLayout,
    onLayoutSelected: (DesktopNavigationLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            item {
                Text(
                    text = stringResource(Res.string.settings_appearance_desktop_navigation_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }

            itemsIndexed(DesktopNavigationLayout.entries) { index, layout ->
                if (index > 0) {
                    NuvioBottomSheetDivider()
                }
                NuvioBottomSheetActionRow(
                    title = stringResource(layout.labelRes),
                    onClick = {
                        onLayoutSelected(layout)
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                    trailingContent = {
                        if (layout == selectedLayout) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(Res.string.cd_selected),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavBarStyleBottomSheet(
    selectedStyle: NavBarStyle,
    isTablet: Boolean = true,
    desktopNavigationLayout: DesktopNavigationLayout = DesktopNavigationLayout.Default,
    onStyleSelected: (NavBarStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val isSidebarActive = isDesktop && isTablet && desktopNavigationLayout == DesktopNavigationLayout.Sidebar
    val availableStyles = remember(isDesktop, isTablet) {
        when {
            isDesktop -> listOf(NavBarStyle.ADAPTIVE, NavBarStyle.EXPANDED, NavBarStyle.COMPACT)
            isIos -> NavBarStyle.entries.filter { it != NavBarStyle.CLASSIC }
            else -> NavBarStyle.entries
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            item {
                Text(
                    text = if (isSidebarActive) {
                        stringResource(Res.string.settings_appearance_sidebar_style_sheet_title)
                    } else if (isDesktop) {
                        stringResource(Res.string.settings_appearance_top_bar_style_sheet_title)
                    } else {
                        stringResource(Res.string.settings_appearance_nav_bar_style_sheet_title)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }

            itemsIndexed(availableStyles) { index, style ->
                if (index > 0) {
                    NuvioBottomSheetDivider()
                }
                val rowTitle = stringResource(style.labelRes)
                NuvioBottomSheetActionRow(
                    title = rowTitle,
                    onClick = {
                        onStyleSelected(style)
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                    trailingContent = {
                        if (style == selectedStyle) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(Res.string.cd_selected),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}
