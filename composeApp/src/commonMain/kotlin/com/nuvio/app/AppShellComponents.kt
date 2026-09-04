package com.nuvio.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DisintegrationRequest
import com.nuvio.app.core.ui.LocalNuvioNavBarScrollState
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioNavBarScrollState
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.CloudLibraryFile
import com.nuvio.app.features.cloud.CloudLibraryItem
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeScreen
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryScreen
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibrarySortOption
import com.nuvio.app.features.player.PlayerBackReleaseGuard
import com.nuvio.app.features.player.PlayerBackRequest
import com.nuvio.app.features.profiles.ActiveProfileMiniAvatar
import com.nuvio.app.features.profiles.AvatarCatalogItem
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.MAX_PROFILES
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileBackgroundBackdrop
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileSwitcherTab
import com.nuvio.app.features.profiles.SidebarProfileSwitcherStack
import com.nuvio.app.features.search.SearchScreen
import com.nuvio.app.features.settings.AppBrandWordmark
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.features.settings.SettingsScreen
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.isDesktop
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.NuvioNavigator
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.flow.Flow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_brand_name
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_profile
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.compose_nav_settings
import nuvio.composeapp.generated.resources.compose_settings_page_root
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

internal val DesktopSidebarCollapsedWidth = 68.dp
internal val DesktopSidebarExpandedWidth = 192.dp
private val DesktopSidebarExpandedContentWidth = 156.dp
private val DesktopSidebarItemHeight = 56.dp
private val DesktopSidebarIconSlotSize = 38.dp
private val DesktopSidebarIconSize = NuvioTokens.Icon.lg
private val DesktopSidebarProfileStackRowHeight = 44.dp
private val DesktopSidebarProfileStackRowGap = 4.dp
private val DesktopSidebarProfileStackTopGap = 6.dp
private val DesktopSidebarProfileStackNavGap = 12.dp

@Composable
internal fun rememberGuardedPopBackStack(
    navController: NuvioNavigator,
    route: AppRoute,
    beforePop: () -> Unit = {},
): () -> Unit {
    var popHandled by remember(route) { mutableStateOf(false) }

    return remember(navController, route, popHandled, beforePop) {
        {
            if (!popHandled && navController.currentRoute == route) {
                popHandled = true
                beforePop()
                navController.popBackStack(expectedRoute = route)
            }
        }
    }
}

internal data class AppTabState(
    val searchListState: LazyListState,
    val homeContentGeneration: Int = 0,
    val searchFocusRequestCount: Int = 0,
    val tabsRouteActiveState: State<Boolean>,
    val topChromePadding: Dp? = null,
    val libraryDisintegrationRequest: DisintegrationRequest<String>? = null,
    val continueWatchingDisintegrationRequest: DisintegrationRequest<String>? = null,
    val requestedSettingsPageName: String? = null,
)

internal data class AppTabRequests(
    val homeScrollToTopRequests: Flow<Unit>,
    val searchScrollToTopRequests: Flow<Unit>,
    val libraryScrollToTopRequests: Flow<Unit>,
    val settingsRootActionRequests: Flow<Unit>,
)

internal data class AppTabActions(
    val onCatalogClick: ((HomeCatalogSection) -> Unit)? = null,
    val onPosterClick: ((MetaPreview) -> Unit)? = null,
    val onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    val onLibraryPosterClick: ((LibraryItem) -> Unit)? = null,
    val onLibraryPosterLongClick: ((LibraryItem, LibrarySection) -> Unit)? = null,
    val onLibrarySectionViewAllClick: ((LibrarySection, LibrarySortOption) -> Unit)? = null,
    val onCloudFilePlay: ((CloudLibraryItem, CloudLibraryFile) -> Unit)? = null,
    val onConnectCloudClick: (() -> Unit)? = null,
    val onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)? = null,
    val onContinueWatchingLongPress: ((ContinueWatchingItem) -> Unit)? = null,
    val onSwitchProfile: (() -> Unit)? = null,
    val onSettingsPageClick: ((pageName: String, title: String) -> Unit)? = null,
    val onHomescreenSettingsClick: () -> Unit = {},
    val onMetaScreenSettingsClick: () -> Unit = {},
    val onContinueWatchingSettingsClick: () -> Unit = {},
    val onDownloadsSettingsClick: () -> Unit = {},
    val onAddonsSettingsClick: () -> Unit = {},
    val onPluginsSettingsClick: () -> Unit = {},
    val onAccountSettingsClick: () -> Unit = {},
    val onSupportersContributorsSettingsClick: () -> Unit = {},
    val onLicensesAttributionsSettingsClick: () -> Unit = {},
    val onCheckForUpdatesClick: (() -> Unit)? = null,
    val onTestUpdateBannerClick: (() -> Unit)? = null,
    val onCollectionsSettingsClick: () -> Unit = {},
    val onFolderClick: ((collectionId: String, folderId: String) -> Unit)? = null,
    val onRequestedSettingsPageConsumed: () -> Unit = {},
    val onInitialHomeContentRendered: () -> Unit = {},
)

@Composable
internal fun rememberGuardedPlayerPopBackStack(
    navController: NuvioNavigator,
    route: AppRoute,
    beforePop: () -> Unit = {},
): PlayerBackRequest {
    val guard = remember(route) { PlayerBackReleaseGuard() }

    return remember(navController, route, beforePop, guard) {
        { releaseBeforeBack ->
            guard.request(
                canStart = {
                    navController.currentRoute == route &&
                        navController.canPopBackStack(expectedRoute = route)
                },
                releaseBeforeBack = releaseBeforeBack,
                beforePop = beforePop,
                pop = {
                    navController.currentRoute == route &&
                        navController.popBackStack(expectedRoute = route)
                },
            )
        }
    }
}

@Composable
internal fun AppTabHost(
    selectedTab: AppScreenTab,
    requests: AppTabRequests,
    state: AppTabState,
    actions: AppTabActions,
    modifier: Modifier = Modifier,
) {
    val tabStateHolder = rememberSaveableStateHolder()
    val isHomeSelected = selectedTab == AppScreenTab.Home

    Box(modifier = modifier.fillMaxSize()) {
        tabStateHolder.SaveableStateProvider(AppScreenTab.Home.name) {
            key(state.homeContentGeneration) {
                HomeScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (isHomeSelected) 1f else 0f)
                        .alpha(if (isHomeSelected) 1f else 0f),
                    topChromePadding = state.topChromePadding,
                    animateCollectionGifs = state.tabsRouteActiveState.value && isHomeSelected,
                    scrollToTopRequests = requests.homeScrollToTopRequests,
                    onCatalogClick = actions.onCatalogClick,
                    onPosterClick = actions.onPosterClick,
                    onPosterLongClick = actions.onPosterLongClick,
                    onContinueWatchingClick = actions.onContinueWatchingClick,
                    onContinueWatchingLongPress = actions.onContinueWatchingLongPress,
                    continueWatchingDisintegrationRequest = state.continueWatchingDisintegrationRequest,
                    onFolderClick = actions.onFolderClick,
                    onFirstCatalogRendered = actions.onInitialHomeContentRendered,
                )
            }
        }

        if (!isHomeSelected) {
            tabStateHolder.SaveableStateProvider(selectedTab.name) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1f),
                ) {
                    when (selectedTab) {
                        AppScreenTab.Home -> Unit

                        AppScreenTab.Search -> {
                            SearchScreen(
                                modifier = Modifier.fillMaxSize(),
                                listState = state.searchListState,
                                topChromePadding = state.topChromePadding,
                                onPosterClick = actions.onPosterClick,
                                onPosterLongClick = actions.onPosterLongClick,
                                searchFocusRequestCount = state.searchFocusRequestCount,
                                scrollToTopRequests = requests.searchScrollToTopRequests,
                            )
                        }

                        AppScreenTab.Library -> {
                            LibraryScreen(
                                modifier = Modifier.fillMaxSize(),
                                topChromePadding = state.topChromePadding,
                                scrollToTopRequests = requests.libraryScrollToTopRequests,
                                onPosterClick = actions.onLibraryPosterClick,
                                onPosterLongClick = actions.onLibraryPosterLongClick,
                                onSectionViewAllClick = actions.onLibrarySectionViewAllClick,
                                onCloudFilePlay = actions.onCloudFilePlay,
                                onConnectCloudClick = actions.onConnectCloudClick,
                                disintegrationRequest = state.libraryDisintegrationRequest,
                            )
                        }

                        AppScreenTab.Settings -> {
                            SettingsScreen(
                                modifier = Modifier.fillMaxSize(),
                                topChromePadding = state.topChromePadding,
                                rootActionRequests = requests.settingsRootActionRequests,
                                requestedPageName = state.requestedSettingsPageName,
                                onRequestedPageConsumed = actions.onRequestedSettingsPageConsumed,
                                rootActionsEnabled = state.tabsRouteActiveState.value,
                                onSwitchProfile = actions.onSwitchProfile,
                                onHomescreenClick = actions.onHomescreenSettingsClick,
                                onMetaScreenClick = actions.onMetaScreenSettingsClick,
                                onContinueWatchingClick = actions.onContinueWatchingSettingsClick,
                                onDownloadsClick = actions.onDownloadsSettingsClick,
                                onAddonsClick = actions.onAddonsSettingsClick,
                                onPluginsClick = actions.onPluginsSettingsClick,
                                onAccountClick = actions.onAccountSettingsClick,
                                onSupportersContributorsClick = actions.onSupportersContributorsSettingsClick,
                                onLicensesAttributionsClick = actions.onLicensesAttributionsSettingsClick,
                                onCheckForUpdatesClick = actions.onCheckForUpdatesClick,
                                onTestUpdateBannerClick = actions.onTestUpdateBannerClick,
                                onCollectionsClick = actions.onCollectionsSettingsClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TabletFloatingTopBar(
    selectedTab: AppScreenTab,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
    navBarStyleSetting: NavBarStyle = NavBarStyle.ADAPTIVE,
    isHeroEnabled: Boolean = true,
    hazeState: HazeState? = null,
    scrollState: NuvioNavBarScrollState? = null,
    windowWidth: Dp? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var isProfileSwitcherOpen by remember { mutableStateOf(false) }

    val isNarrowWindow = windowWidth != null && windowWidth < 800.dp
    val isLargeWindow = windowWidth != null && windowWidth > 1600.dp

    val hasSettingsDrawer = selectedTab == AppScreenTab.Settings && (windowWidth == null || windowWidth >= 960.dp)
    val supportsScrollFrostedHaze = selectedTab == AppScreenTab.Home || hasSettingsDrawer
    val scrollThreshold = if (selectedTab == AppScreenTab.Settings) 70f else 35f
    val isScrolledAwayFromTop = scrollState != null && scrollState.totalScrollOffset > scrollThreshold
    val isScrolled = supportsScrollFrostedHaze && isScrolledAwayFromTop
    val isFrosted = isScrolled || isHovered || isProfileSwitcherOpen

    val surfaceColor by animateColorAsState(
        targetValue = if (isFrosted) {
            Color(0xFF1C1C1E).copy(alpha = 0.30f)
        } else {
            Color(0xFF0F0F11).copy(alpha = 0.20f)
        },
        animationSpec = tween(
            durationMillis = 320,
            easing = FastOutSlowInEasing,
        ),
        label = "top_bar_surface_color",
    )

    val isHeroPresentOnHome = selectedTab == AppScreenTab.Home && isHeroEnabled

    val targetLabelFraction = when (navBarStyleSetting) {
        NavBarStyle.EXPANDED -> 1f
        NavBarStyle.COMPACT -> 0f
        else -> { // ADAPTIVE
            if (isHovered || isProfileSwitcherOpen) {
                1f
            } else if (isHeroPresentOnHome) {
                // Home with hero enabled: compact at top and when scrolled (unless hovered)
                0f
            } else if (selectedTab == AppScreenTab.Home) {
                // Home with hero disabled: expanded at top, collapses to compact when scrolled down
                if (isScrolledAwayFromTop) 0f else 1f
            } else {
                // Library, Search/Discover, Settings: ALWAYS expanded in Adaptive mode
                1f
            }
        }
    }
    val labelFraction by animateFloatAsState(
        targetValue = targetLabelFraction,
        animationSpec = tween(
            durationMillis = 320,
            easing = FastOutSlowInEasing,
        ),
        label = "top_bar_label_fraction",
    )

    val frostedSheenAlpha by animateFloatAsState(
        targetValue = if (isFrosted) 1f else 0f,
        animationSpec = tween(
            durationMillis = 320,
            easing = FastOutSlowInEasing,
        ),
        label = "top_bar_sheen_alpha",
    )

    val pillHeight = when {
        isNarrowWindow -> 34.dp
        isLargeWindow -> 42.dp
        else -> 38.dp
    }
    val navIconSize = when {
        isNarrowWindow -> 16.dp
        isLargeWindow -> 20.dp
        else -> NuvioTokens.Space.s18
    }
    val avatarSize = when {
        isNarrowWindow -> 24
        isLargeWindow -> 30
        else -> 28
    }
    val iconCollapsedPadding = when {
        isNarrowWindow -> 9.dp
        isLargeWindow -> 11.dp
        else -> 10.dp
    }
    val avatarCollapsedPadding = when {
        isNarrowWindow -> 5.dp
        isLargeWindow -> 6.dp
        else -> 5.dp
    }
    val avatarExpandedStartPadding = when {
        isNarrowWindow -> 6.dp
        isLargeWindow -> 10.dp
        else -> 8.dp
    }
    val avatarExpandedEndPadding = when {
        isNarrowWindow -> 14.dp
        isLargeWindow -> 18.dp
        else -> 16.dp
    }
    val expandedHorizontalPadding = when {
        isNarrowWindow -> 10.dp
        isLargeWindow -> 14.dp
        else -> 12.dp
    }
    val collapsedItemSpacing = when {
        isNarrowWindow -> 4.dp
        isLargeWindow -> 8.dp
        else -> 6.dp
    }
    val expandedItemSpacing = when {
        isNarrowWindow -> 4.dp
        isLargeWindow -> 8.dp
        else -> tokens.spacing.controlGap
    }
    val itemSpacing = collapsedItemSpacing * (1f - labelFraction) + expandedItemSpacing * labelFraction
    val baseTopPadding = when {
        isNarrowWindow -> 6.dp
        isLargeWindow -> 14.dp
        else -> NuvioTokens.Space.s10
    }

    val labelTextStyle = when {
        isNarrowWindow -> MaterialTheme.typography.labelMedium
        isLargeWindow -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.labelLarge
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusBarPadding + baseTopPadding, bottom = tokens.spacing.controlGap),
        contentAlignment = Alignment.TopCenter,
    ) {
        val chipShape = tokens.shapes.chip
        val pillModifier = Modifier
            .clip(chipShape)
            .then(
                if (isFrosted && hazeState != null) {
                    Modifier.hazeEffect(state = hazeState) {
                        blurRadius = 14.dp
                    }
                } else {
                    Modifier
                },
            )
            .hoverable(interactionSource)

        Surface(
            color = surfaceColor,
            shape = chipShape,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = pillModifier,
        ) {
            Box {
                if (frostedSheenAlpha > 0.01f) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(chipShape)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.14f * frostedSheenAlpha),
                                        Color.White.copy(alpha = 0.03f * frostedSheenAlpha),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                }

                Row(
                    modifier = Modifier.padding(
                        horizontal = if (isNarrowWindow) 8.dp else if (isLargeWindow) 12.dp else NuvioTokens.Space.s10,
                        vertical = if (isNarrowWindow) 4.dp else tokens.spacing.controlGap,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TabletTopPillItem(
                        label = stringResource(Res.string.compose_nav_home),
                        selected = selectedTab == AppScreenTab.Home,
                        onClick = { onTabSelected(AppScreenTab.Home) },
                        labelFraction = labelFraction,
                        pillHeight = pillHeight,
                        expandedHorizontalPadding = expandedHorizontalPadding,
                        collapsedHorizontalPadding = iconCollapsedPadding,
                        textStyle = labelTextStyle,
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Home,
                                contentDescription = stringResource(Res.string.compose_nav_home),
                                modifier = Modifier.size(navIconSize),
                                tint = if (selectedTab == AppScreenTab.Home) {
                                    tokens.colors.textPrimary
                                } else {
                                    Color.White.copy(alpha = 0.70f)
                                },
                            )
                        },
                    )
                    TabletTopPillItem(
                        label = stringResource(Res.string.compose_nav_search),
                        selected = selectedTab == AppScreenTab.Search,
                        onClick = { onTabSelected(AppScreenTab.Search) },
                        labelFraction = labelFraction,
                        pillHeight = pillHeight,
                        expandedHorizontalPadding = expandedHorizontalPadding,
                        collapsedHorizontalPadding = iconCollapsedPadding,
                        textStyle = labelTextStyle,
                        icon = {
                            Icon(
                                painter = painterResource(Res.drawable.sidebar_search),
                                contentDescription = stringResource(Res.string.compose_nav_search),
                                modifier = Modifier.size(navIconSize),
                                tint = if (selectedTab == AppScreenTab.Search) {
                                    tokens.colors.textPrimary
                                } else {
                                    Color.White.copy(alpha = 0.70f)
                                },
                            )
                        },
                    )
                    TabletTopPillItem(
                        label = stringResource(Res.string.compose_nav_library),
                        selected = selectedTab == AppScreenTab.Library,
                        onClick = { onTabSelected(AppScreenTab.Library) },
                        labelFraction = labelFraction,
                        pillHeight = pillHeight,
                        expandedHorizontalPadding = expandedHorizontalPadding,
                        collapsedHorizontalPadding = iconCollapsedPadding,
                        textStyle = labelTextStyle,
                        icon = {
                            Icon(
                                painter = painterResource(Res.drawable.sidebar_library),
                                contentDescription = stringResource(Res.string.compose_nav_library),
                                modifier = Modifier.size(navIconSize),
                                tint = if (selectedTab == AppScreenTab.Library) {
                                    tokens.colors.textPrimary
                                } else {
                                    Color.White.copy(alpha = 0.70f)
                                },
                            )
                        },
                    )
                    TabletTopPillItem(
                        label = stringResource(Res.string.compose_nav_settings),
                        selected = selectedTab == AppScreenTab.Settings,
                        onClick = { onTabSelected(AppScreenTab.Settings) },
                        labelFraction = labelFraction,
                        pillHeight = pillHeight,
                        expandedHorizontalPadding = expandedHorizontalPadding,
                        collapsedHorizontalPadding = avatarCollapsedPadding,
                        expandedStartPadding = avatarExpandedStartPadding,
                        expandedEndPadding = avatarExpandedEndPadding,
                        textStyle = labelTextStyle,
                        icon = {
                            ProfileSwitcherTab(
                                selected = selectedTab == AppScreenTab.Settings,
                                onClick = { onTabSelected(AppScreenTab.Settings) },
                                onProfileSelected = onProfileSelected,
                                onAddProfileRequested = onAddProfileRequested,
                                onPopupStateChanged = { isProfileSwitcherOpen = it },
                                avatarSize = avatarSize,
                                hazeState = hazeState,
                                popupAlignment = Alignment.TopCenter,
                            )
                        },
                    )
                }
            }
        }
    }
}

internal fun ContinueWatchingItem.isCloudLibraryContinueWatchingItem(): Boolean =
    parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)

@Composable
private fun TabletTopPillItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    labelFraction: Float = 1f,
    pillHeight: Dp = 38.dp,
    expandedHorizontalPadding: Dp = 12.dp,
    collapsedHorizontalPadding: Dp = 10.dp,
    expandedStartPadding: Dp = expandedHorizontalPadding,
    expandedEndPadding: Dp = expandedHorizontalPadding,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    icon: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val selectedBgColor by animateColorAsState(
        targetValue = if (selected) tokens.colors.accent.copy(alpha = 0.18f) else Color.Transparent,
        label = "pill_bg_color",
    )
    val itemShape = tokens.shapes.chip

    val startPadding = if (labelFraction > 0.05f) expandedStartPadding else collapsedHorizontalPadding
    val endPadding = if (labelFraction > 0.05f) expandedEndPadding else collapsedHorizontalPadding

    Surface(
        onClick = onClick,
        color = selectedBgColor,
        shape = itemShape,
        tonalElevation = if (selected) tokens.elevation.raised else tokens.elevation.flat,
        modifier = Modifier
            .height(pillHeight)
            .clip(itemShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = startPadding, end = endPadding),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            AnimatedVisibility(
                visible = labelFraction > 0.05f,
                enter = expandHorizontally(
                    animationSpec = tween(320, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Start,
                ) + fadeIn(tween(250)),
                exit = shrinkHorizontally(
                    animationSpec = tween(320, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Start,
                ) + fadeOut(tween(200)),
            ) {
                Text(
                    text = label,
                    style = textStyle,
                    color = if (selected) {
                        tokens.colors.textPrimary
                    } else {
                        Color.White.copy(alpha = 0.75f)
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
internal fun AppLoadingContent(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppBrandWordmark(
                contentDescription = stringResource(Res.string.app_brand_name),
                modifier = Modifier
                    .fillMaxWidth(0.48f)
                    .height(44.dp),
            )
            Spacer(modifier = Modifier.height(tokens.spacing.sectionGap))
            NuvioLoadingIndicator(color = tokens.colors.accent)
        }
    }
}

@Composable
internal fun AppLaunchOverlay(
    profile: NuvioProfile?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.zIndex(NuvioTokens.Z.dialog),
    ) {
        ProfileBackgroundBackdrop(
            profile = profile,
            modifier = Modifier.fillMaxSize(),
        )
        AppLoadingContent(modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun DesktopHoverSidebar(
    selectedTab: AppScreenTab,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
    sidebarExpanded: Boolean = false,
    sidebarWidth: Dp = DesktopSidebarCollapsedWidth,
    hoverSource: MutableInteractionSource = remember { MutableInteractionSource() },
    profileStackVisible: Boolean = false,
    onProfileStackVisibleChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val activeProfile = profileState.activeProfile
    val profiles = profileState.profiles
    val activeProfileName = activeProfile?.name ?: stringResource(Res.string.compose_nav_profile)
    val profileTopPadding = statusBarPadding + 18.dp
    fun selectTab(tab: AppScreenTab) {
        onProfileStackVisibleChange(false)
        onTabSelected(tab)
    }

    Surface(
        modifier = modifier
            .width(sidebarWidth)
            .fillMaxHeight()
            .hoverable(hoverSource)
            .zIndex(NuvioTokens.Z.navigation),
        color = tokens.colors.background,
        contentColor = tokens.colors.textPrimary,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
        ) {
            val profileStackRows = profiles.size + if (profiles.size < MAX_PROFILES) 1 else 0
            val profileStackHeight = if (profileStackRows > 0) {
                DesktopSidebarProfileStackRowHeight * profileStackRows +
                    DesktopSidebarProfileStackRowGap * (profileStackRows - 1)
            } else {
                0.dp
            }
            val profileStackTop = profileTopPadding + DesktopSidebarItemHeight + DesktopSidebarProfileStackTopGap
            val minNavTop = if (profileStackVisible) {
                profileStackTop + profileStackHeight + DesktopSidebarProfileStackNavGap
            } else {
                0.dp
            }
            val navColumnHeight = DesktopSidebarItemHeight * AppScreenTab.entries.size
            val centeredNavTop = ((maxHeight - navColumnHeight) / 2).coerceAtLeast(0.dp)
            val availableNavOffset = (maxHeight - navColumnHeight - centeredNavTop).coerceAtLeast(0.dp)
            val navColumnOffset = (minNavTop - centeredNavTop)
                .coerceIn(0.dp, availableNavOffset)
            val animatedNavColumnOffset by animateDpAsState(
                targetValue = navColumnOffset,
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                label = "desktop_sidebar_nav_offset",
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = profileTopPadding)
                    .fillMaxWidth()
                    .height(DesktopSidebarItemHeight)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onProfileStackVisibleChange(!profileStackVisible) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                DesktopSidebarProfileTrigger(
                    profile = activeProfile,
                    avatars = avatars,
                    label = activeProfileName,
                    expanded = sidebarExpanded,
                )
            }

            if (profileStackVisible) {
                SidebarProfileSwitcherStack(
                    onProfileSelected = onProfileSelected,
                    onAddProfileRequested = onAddProfileRequested,
                    onDismissRequest = { onProfileStackVisibleChange(false) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = profileStackTop)
                        .width(DesktopSidebarExpandedContentWidth)
                        .zIndex(NuvioTokens.Z.sheet),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = animatedNavColumnOffset)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DesktopSidebarItem(
                    label = stringResource(Res.string.compose_nav_home),
                    selected = selectedTab == AppScreenTab.Home,
                    expanded = sidebarExpanded,
                    onClick = { selectTab(AppScreenTab.Home) },
                ) { color ->
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = stringResource(Res.string.compose_nav_home),
                        modifier = Modifier.size(DesktopSidebarIconSize),
                        tint = color,
                    )
                }
                DesktopSidebarItem(
                    label = stringResource(Res.string.compose_nav_search),
                    selected = selectedTab == AppScreenTab.Search,
                    expanded = sidebarExpanded,
                    onClick = { selectTab(AppScreenTab.Search) },
                ) { color ->
                    Icon(
                        painter = painterResource(Res.drawable.sidebar_search),
                        contentDescription = stringResource(Res.string.compose_nav_search),
                        modifier = Modifier.size(DesktopSidebarIconSize),
                        tint = color,
                    )
                }
                DesktopSidebarItem(
                    label = stringResource(Res.string.compose_nav_library),
                    selected = selectedTab == AppScreenTab.Library,
                    expanded = sidebarExpanded,
                    onClick = { selectTab(AppScreenTab.Library) },
                ) { color ->
                    Icon(
                        painter = painterResource(Res.drawable.sidebar_library),
                        contentDescription = stringResource(Res.string.compose_nav_library),
                        modifier = Modifier.size(DesktopSidebarIconSize),
                        tint = color,
                    )
                }
                DesktopSidebarItem(
                    label = stringResource(Res.string.compose_settings_page_root),
                    selected = selectedTab == AppScreenTab.Settings,
                    expanded = sidebarExpanded,
                    onClick = { selectTab(AppScreenTab.Settings) },
                ) { color ->
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = stringResource(Res.string.compose_settings_page_root),
                        modifier = Modifier.size(DesktopSidebarIconSize),
                        tint = color,
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopSidebarProfileTrigger(
    profile: NuvioProfile?,
    avatars: List<AvatarCatalogItem>,
    label: String,
    expanded: Boolean,
) {
    val tokens = MaterialTheme.nuvio

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(DesktopSidebarIconSlotSize),
                contentAlignment = Alignment.Center,
            ) {
                ActiveProfileMiniAvatar(
                    profile = profile,
                    avatars = avatars,
                    selected = false,
                    size = 32,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(animationSpec = tween(160)) + expandHorizontally(
                    expandFrom = Alignment.Start,
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                ),
                exit = shrinkHorizontally(
                    shrinkTowards = Alignment.Start,
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                ) + fadeOut(
                    animationSpec = tween(80, delayMillis = 120),
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopSidebarItem(
    label: String,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val contentColor = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted
    val iconColor = if (selected) tokens.colors.onAccent else contentColor

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(DesktopSidebarItemHeight)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(DesktopSidebarIconSlotSize),
                color = if (selected) tokens.colors.accent else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    icon(iconColor)
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(animationSpec = tween(160)) + expandHorizontally(
                    expandFrom = Alignment.Start,
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                ),
                exit = shrinkHorizontally(
                    shrinkTowards = Alignment.Start,
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                ) + fadeOut(
                    animationSpec = tween(80, delayMillis = 120),
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}
