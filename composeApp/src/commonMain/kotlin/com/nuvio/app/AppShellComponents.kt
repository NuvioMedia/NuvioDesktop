package com.nuvio.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nuvio.app.core.ui.DisintegrationRequest
import com.nuvio.app.core.ui.NuvioLoadingIndicator
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
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileBackgroundBackdrop
import com.nuvio.app.features.profiles.ProfileSwitcherTab
import com.nuvio.app.features.search.SearchScreen
import com.nuvio.app.features.settings.AppBrandWordmark
import com.nuvio.app.features.settings.SettingsScreen
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.NuvioNavigator
import kotlinx.coroutines.flow.Flow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_brand_name
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_profile
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.State
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.profiles.ActiveProfileMiniAvatar
import com.nuvio.app.features.profiles.AvatarCatalogItem
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.MAX_PROFILES
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.SidebarProfileSwitcherStack
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.compose_settings_page_root
import com.nuvio.app.features.player.PlayerBackRequest
import com.nuvio.app.features.player.PlayerBackReleaseGuard

internal val DesktopSidebarCollapsedWidth = 84.dp
private val DesktopSidebarExpandedWidth = 208.dp
private val DesktopSidebarExpandedContentWidth = 168.dp
private val DesktopSidebarItemHeight = 58.dp
private val DesktopSidebarIconSlotSize = 42.dp
private val DesktopSidebarIconSize = NuvioTokens.Icon.lg
private val DesktopSidebarProfileStackRowHeight = 40.dp
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
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusBarPadding + NuvioTokens.Space.s10, bottom = tokens.spacing.controlGap),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            color = tokens.colors.surface.copy(alpha = tokens.opacity.visible - tokens.opacity.subtle),
            shape = tokens.shapes.chip,
            tonalElevation = tokens.elevation.playerControls,
            shadowElevation = tokens.elevation.overlay,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s10, vertical = tokens.spacing.controlGap),
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabletTopPillItem(
                    label = stringResource(Res.string.compose_nav_home),
                    selected = selectedTab == AppScreenTab.Home,
                    onClick = { onTabSelected(AppScreenTab.Home) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = stringResource(Res.string.compose_nav_home),
                            modifier = Modifier.size(NuvioTokens.Space.s18),
                            tint = if (selectedTab == AppScreenTab.Home) {
                                tokens.colors.textPrimary
                            } else {
                                tokens.colors.textMuted
                            },
                        )
                    },
                )
                TabletTopPillItem(
                    label = stringResource(Res.string.compose_nav_search),
                    selected = selectedTab == AppScreenTab.Search,
                    onClick = { onTabSelected(AppScreenTab.Search) },
                    icon = {
                        Icon(
                            painter = painterResource(Res.drawable.sidebar_search),
                            contentDescription = stringResource(Res.string.compose_nav_search),
                            modifier = Modifier.size(NuvioTokens.Space.s18),
                            tint = if (selectedTab == AppScreenTab.Search) {
                                tokens.colors.textPrimary
                            } else {
                                tokens.colors.textMuted
                            },
                        )
                    },
                )
                TabletTopPillItem(
                    label = stringResource(Res.string.compose_nav_library),
                    selected = selectedTab == AppScreenTab.Library,
                    onClick = { onTabSelected(AppScreenTab.Library) },
                    icon = {
                        Icon(
                            painter = painterResource(Res.drawable.sidebar_library),
                            contentDescription = stringResource(Res.string.compose_nav_library),
                            modifier = Modifier.size(NuvioTokens.Space.s18),
                            tint = if (selectedTab == AppScreenTab.Library) {
                                tokens.colors.textPrimary
                            } else {
                                tokens.colors.textMuted
                            },
                        )
                    },
                )
                Surface(
                    color = if (selectedTab == AppScreenTab.Settings) {
                        tokens.colors.overlaySelected
                    } else {
                        tokens.colors.surface
                    },
                    shape = tokens.shapes.chip,
                    modifier = Modifier.clickable { onTabSelected(AppScreenTab.Settings) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = tokens.spacing.listGap, vertical = tokens.spacing.controlGap),
                        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProfileSwitcherTab(
                            selected = selectedTab == AppScreenTab.Settings,
                            onClick = { onTabSelected(AppScreenTab.Settings) },
                            onProfileSelected = onProfileSelected,
                            onAddProfileRequested = onAddProfileRequested,
                        )
                        Text(
                            text = stringResource(Res.string.compose_nav_profile),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selectedTab == AppScreenTab.Settings) {
                                tokens.colors.textPrimary
                            } else {
                                tokens.colors.textMuted
                            },
                        )
                    }
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
    icon: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        color = if (selected) tokens.colors.overlaySelected else tokens.colors.surface,
        shape = tokens.shapes.chip,
        tonalElevation = if (selected) tokens.elevation.raised else tokens.elevation.flat,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = tokens.components.chipHorizontalPadding, vertical = NuvioTokens.Space.s10),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    tokens.colors.textPrimary
                } else {
                    tokens.colors.textMuted
                },
            )
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
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val activeProfile = profileState.activeProfile
    val profiles = profileState.profiles
    val activeProfileName = activeProfile?.name ?: stringResource(Res.string.compose_nav_profile)
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    var profileStackVisible by remember { mutableStateOf(false) }
    val sidebarExpanded = hovered || profileStackVisible
    val profileTopPadding = statusBarPadding + 18.dp
    fun selectTab(tab: AppScreenTab) {
        profileStackVisible = false
        onTabSelected(tab)
    }
    val sidebarWidth by animateDpAsState(
        targetValue = if (sidebarExpanded) DesktopSidebarExpandedWidth else DesktopSidebarCollapsedWidth,
        animationSpec = tween(durationMillis = 180),
        label = "desktop_sidebar_width",
    )

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
                animationSpec = tween(durationMillis = 180),
                label = "desktop_sidebar_nav_offset",
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = profileTopPadding)
                    .fillMaxWidth()
                    .height(DesktopSidebarItemHeight)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { profileStackVisible = !profileStackVisible },
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
                    onDismissRequest = { profileStackVisible = false },
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
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.width(
                    if (expanded) DesktopSidebarExpandedContentWidth else DesktopSidebarIconSlotSize,
                ),
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
                if (expanded) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
        modifier = Modifier
            .fillMaxWidth()
            .height(DesktopSidebarItemHeight)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.width(
                    if (expanded) DesktopSidebarExpandedContentWidth else DesktopSidebarIconSlotSize,
                ),
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
                if (expanded) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
