package com.nuvio.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.LocalNuvioNavBarScrollState
import com.nuvio.app.core.ui.NuvioNavBarScrollState
import com.nuvio.app.core.ui.NuvioClassicNavigationBar
import com.nuvio.app.core.ui.DesktopNavigationBar
import com.nuvio.app.core.ui.FloatingNavigationBar
import com.nuvio.app.core.ui.FloatingNavigationItem
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.rememberNuvioNavBarScrollState
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileSwitcherTab
import com.nuvio.app.features.settings.DesktopNavigationLayout
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.features.settings.ThemeSettingsRepository
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_profile
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.compose_nav_settings
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.stringResource
@Composable
internal fun MainTabsDestination(
    selectedTab: AppScreenTab,
    initialHomeReady: Boolean,
    rootRouteActive: Boolean,
    useTabletFloatingTabBar: Boolean,
    useNativeNavigation: Boolean,
    useNativeTabBar: Boolean,
    liquidGlassNativeTabBarSupported: Boolean,
    liquidGlassNativeTabBarEnabled: Boolean,
    desktopNavigationLayout: DesktopNavigationLayout,
    requests: AppTabRequests,
    state: AppTabState,
    actions: (isTabletLayout: Boolean) -> AppTabActions,
    onBack: () -> Unit,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
) {
    PlatformBackHandler(enabled = rootRouteActive, onBack = onBack)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val isTabletLayout = useTabletFloatingTabBar || screenWidth >= 768.dp
        val tabActions = remember(actions, isTabletLayout) { actions(isTabletLayout) }
        val useNativeBottomTabs = if (useNativeNavigation) {
            useNativeTabBar
        } else {
            liquidGlassNativeTabBarSupported && liquidGlassNativeTabBarEnabled && initialHomeReady
        }
        val useDesktopSidebar = isDesktop &&
            isTabletLayout &&
            !useNativeBottomTabs &&
            desktopNavigationLayout == DesktopNavigationLayout.Sidebar
        val useDesktopTopBar = isDesktop && !useNativeBottomTabs && !useDesktopSidebar
        val useFloatingTopBar = !isDesktop && isTabletLayout && !useNativeBottomTabs
        val topChromePadding = if (useFloatingTopBar || useDesktopTopBar) {
            val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            max(statusBarPadding + 24.dp, 48.dp) + 64.dp
        } else {
            null
        }
        val tabsRouteActiveState = rememberUpdatedState(rootRouteActive)
        val navBarScrollState = rememberNuvioNavBarScrollState()
        LaunchedEffect(selectedTab) {
            navBarScrollState.switchToTab(selectedTab)
        }
        val navBarHazeState = rememberHazeState()
        val navBarStyleSetting by remember { ThemeSettingsRepository.navBarStyle }.collectAsStateWithLifecycle()
        val homeCatalogSettingsUiState by remember { HomeCatalogSettingsRepository.uiState }.collectAsStateWithLifecycle()

        val sidebarHoverSource = remember { MutableInteractionSource() }
        val isSidebarHovered by sidebarHoverSource.collectIsHoveredAsState()
        var isProfileStackVisible by remember { mutableStateOf(false) }

        val isSidebarExpanded = when (navBarStyleSetting) {
            NavBarStyle.EXPANDED -> true
            NavBarStyle.COMPACT -> isProfileStackVisible
            else -> isSidebarHovered || isProfileStackVisible // ADAPTIVE
        }

        val animatedSidebarWidth by animateDpAsState(
            targetValue = if (isSidebarExpanded) DesktopSidebarExpandedWidth else DesktopSidebarCollapsedWidth,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            label = "desktop_sidebar_width",
        )

        val isSidebarAlwaysExpanded = useDesktopSidebar && navBarStyleSetting == NavBarStyle.EXPANDED
        val contentStartPadding = if (useDesktopSidebar) {
            if (isSidebarAlwaysExpanded) DesktopSidebarExpandedWidth else DesktopSidebarCollapsedWidth
        } else {
            0.dp
        }
        val navBarGlowEnabled by ThemeSettingsRepository.navBarGlowEnabled.collectAsStateWithLifecycle()
        var isTopProfileSwitcherOpen by remember { mutableStateOf(false) }
        val floatingNavigationItems = listOf(
            FloatingNavigationItem(
                selected = selectedTab == AppScreenTab.Home,
                onClick = { onTabSelected(AppScreenTab.Home) },
                icon = Icons.Filled.Home,
                label = stringResource(Res.string.compose_nav_home),
            ),
            FloatingNavigationItem(
                selected = selectedTab == AppScreenTab.Search,
                onClick = { onTabSelected(AppScreenTab.Search) },
                drawable = Res.drawable.sidebar_search,
                label = stringResource(Res.string.compose_nav_search),
            ),
            FloatingNavigationItem(
                selected = selectedTab == AppScreenTab.Library,
                onClick = { onTabSelected(AppScreenTab.Library) },
                drawable = Res.drawable.sidebar_library,
                label = stringResource(Res.string.compose_nav_library),
            ),
            FloatingNavigationItem(
                selected = selectedTab == AppScreenTab.Settings,
                onClick = { onTabSelected(AppScreenTab.Settings) },
                label = stringResource(if (isDesktop) Res.string.compose_nav_settings else Res.string.compose_nav_profile),
                content = { onClick ->
                    ProfileSwitcherTab(
                        selected = selectedTab == AppScreenTab.Settings,
                        onClick = onClick,
                        onProfileSelected = onProfileSelected,
                        onAddProfileRequested = onAddProfileRequested,
                        hazeState = navBarHazeState,
                        onPopupStateChanged = { isTopProfileSwitcherOpen = it },
                        avatarSize = if (isDesktop && screenWidth < 800.dp) 26 else if (isDesktop && screenWidth > 1600.dp) 30 else 28,
                        popupBelowAnchor = isDesktop || isTabletLayout,
                    )
                },
            ),
        )

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (initialHomeReady) 1f else 0f),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (!isDesktop && !isTabletLayout && !useNativeBottomTabs && navBarStyleSetting == NavBarStyle.CLASSIC) {
                    NuvioClassicNavigationBar {
                        NavItem(
                            selected = selectedTab == AppScreenTab.Home,
                            onClick = { onTabSelected(AppScreenTab.Home) },
                            icon = Icons.Filled.Home,
                            contentDescription = stringResource(Res.string.compose_nav_home),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Search,
                            onClick = { onTabSelected(AppScreenTab.Search) },
                            icon = Res.drawable.sidebar_search,
                            contentDescription = stringResource(Res.string.compose_nav_search),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Library,
                            onClick = { onTabSelected(AppScreenTab.Library) },
                            icon = Res.drawable.sidebar_library,
                            contentDescription = stringResource(Res.string.compose_nav_library),
                        )
                        NavItem(
                            selected = selectedTab == AppScreenTab.Settings,
                            onClick = { onTabSelected(AppScreenTab.Settings) },
                        ) {
                            ProfileSwitcherTab(
                                selected = selectedTab == AppScreenTab.Settings,
                                onClick = { onTabSelected(AppScreenTab.Settings) },
                                onProfileSelected = onProfileSelected,
                                onAddProfileRequested = onAddProfileRequested,
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                val requiresNavBarHaze = if (useDesktopTopBar) {
                    true
                } else if (isTabletLayout) {
                    useFloatingTopBar
                } else {
                    !useNativeBottomTabs && navBarStyleSetting != NavBarStyle.CLASSIC
                }
                val shouldAttachNestedScroll = if (isDesktop || isTabletLayout) {
                    true
                } else {
                    navBarStyleSetting == NavBarStyle.ADAPTIVE
                }
                CompositionLocalProvider(
                    LocalNuvioBottomNavigationOverlayPadding provides if (useNativeBottomTabs) 49.dp else if (!isDesktop && !isTabletLayout && navBarStyleSetting != NavBarStyle.CLASSIC) 72.dp else 0.dp,
                    LocalNuvioNavBarScrollState provides navBarScrollState,
                ) {
                    AppTabHost(
                        selectedTab = selectedTab,
                        requests = requests,
                        state = state.copy(
                            tabsRouteActiveState = tabsRouteActiveState,
                            topChromePadding = topChromePadding,
                        ),
                        actions = tabActions,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (requiresNavBarHaze) Modifier.hazeSource(state = navBarHazeState) else Modifier)
                            .then(if (shouldAttachNestedScroll) Modifier.nestedScroll(navBarScrollState.nestedScrollConnection) else Modifier)
                            .padding(innerPadding)
                            .padding(start = contentStartPadding),
                    )
                }

                if (useDesktopSidebar) {
                    DesktopHoverSidebar(
                        selectedTab = selectedTab,
                        onTabSelected = onTabSelected,
                        onProfileSelected = onProfileSelected,
                        onAddProfileRequested = onAddProfileRequested,
                        sidebarExpanded = isSidebarExpanded,
                        sidebarWidth = animatedSidebarWidth,
                        hoverSource = sidebarHoverSource,
                        profileStackVisible = isProfileStackVisible,
                        onProfileStackVisibleChange = { isProfileStackVisible = it },
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }

                if (useFloatingTopBar) {
                    TabletFloatingTopBar(
                        selectedTab = selectedTab,
                        onTabSelected = onTabSelected,
                        onProfileSelected = onProfileSelected,
                        onAddProfileRequested = onAddProfileRequested,
                        navBarStyleSetting = navBarStyleSetting,
                        isHeroEnabled = homeCatalogSettingsUiState.heroEnabled,
                        hazeState = navBarHazeState,
                        scrollState = navBarScrollState,
                        windowWidth = screenWidth,
                    )
                }

                if (isTabletLayout && !useNativeBottomTabs && !isDesktop) {
                    val tabletNavBarScrollState = remember { NuvioNavBarScrollState().apply { collapse() } }
                    FloatingNavigationBar(
                        modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 416.dp),
                        scrollState = tabletNavBarScrollState,
                        hazeState = navBarHazeState,
                        contentPadding = PaddingValues(
                            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp,
                            bottom = 8.dp,
                        ),
                        compactSize = true,
                        items = floatingNavigationItems,
                        glowEnabled = navBarGlowEnabled,
                    )
                }

                if (useDesktopTopBar) {
                    DesktopNavigationBar(
                        items = floatingNavigationItems,
                        modifier = Modifier.align(Alignment.TopCenter),
                        contentPadding = PaddingValues(
                            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp,
                            bottom = 8.dp,
                        ),
                        scrollState = navBarScrollState,
                        hazeState = navBarHazeState,
                        navBarStyle = navBarStyleSetting,
                        isHeroEnabled = homeCatalogSettingsUiState.heroEnabled,
                        profileSwitcherOpen = isTopProfileSwitcherOpen,
                        windowWidth = screenWidth,
                        glowEnabled = navBarGlowEnabled,
                    )
                }

                if (!isDesktop && !isTabletLayout && !useNativeBottomTabs && navBarStyleSetting != NavBarStyle.CLASSIC) {
                    when (navBarStyleSetting) {
                        NavBarStyle.EXPANDED -> navBarScrollState.expand()
                        NavBarStyle.COMPACT -> navBarScrollState.collapse()
                        else -> {}
                    }
                    FloatingNavigationBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        scrollState = navBarScrollState,
                        hazeState = navBarHazeState,
                        items = floatingNavigationItems,
                        glowEnabled = navBarGlowEnabled,
                    )
                }
            }
        }
    }
}
