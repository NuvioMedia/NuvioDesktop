package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.party.PartyMedia
import com.nuvio.app.features.player.ActivePlayerEpisode
import com.nuvio.app.features.player.ExternalPlayerIntentResult
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerScreen
import com.nuvio.app.features.player.PlayerStreamsRepository
import com.nuvio.app.features.streams.StreamLaunchStore
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.watchprogress.ResumePromptRepository
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PlayerRoute
import com.nuvio.app.navigation.StreamRoute

internal fun syncUnderlyingStreamRouteOnPlayerExit(
    navController: NuvioNavigator,
    activeEpisode: ActivePlayerEpisode,
) {
    val underlyingStreamRoute = navController.routes
        .dropLast(1)
        .lastOrNull() as? StreamRoute ?: return
    val existingLaunch = StreamLaunchStore.get(underlyingStreamRoute.launchId) ?: return
    val targetVideoId = activeEpisode.videoId?.takeIf { it.isNotBlank() } ?: existingLaunch.videoId
    val episodeChanged =
        targetVideoId != existingLaunch.videoId ||
            activeEpisode.seasonNumber != existingLaunch.seasonNumber ||
            activeEpisode.episodeNumber != existingLaunch.episodeNumber
    if (!episodeChanged) return

    StreamLaunchStore.update(underlyingStreamRoute.launchId) { current ->
        current.copy(
            videoId = targetVideoId,
            seasonNumber = activeEpisode.seasonNumber,
            episodeNumber = activeEpisode.episodeNumber,
            episodeTitle = activeEpisode.episodeTitle,
            episodeThumbnail = activeEpisode.episodeThumbnail ?: current.episodeThumbnail,
            pauseDescription = activeEpisode.pauseDescription ?: current.pauseDescription,
            resumePositionMs = null,
            resumeProgressFraction = null,
            manualSelection = true,
        )
    }

    val cachedStreams = PlayerStreamsRepository.cachedStreamsFor(
        type = existingLaunch.type,
        videoId = targetVideoId,
        season = activeEpisode.seasonNumber,
        episode = activeEpisode.episodeNumber,
    )
    if (cachedStreams != null) {
        StreamsRepository.restore(
            type = existingLaunch.type,
            videoId = targetVideoId,
            season = activeEpisode.seasonNumber,
            episode = activeEpisode.episodeNumber,
            manualSelection = true,
            state = cachedStreams,
        )
    } else {
        StreamsRepository.clear()
    }
}

@Composable
internal fun PlayerDestination(
    route: PlayerRoute,
    navController: NuvioNavigator,
    externalPlayerId: String?,
    externalPlayerNotConfiguredText: String,
    externalPlayerFailedText: String,
    onExternalPlayerLaunch: (PlayerLaunch) -> Unit,
    launchExternalPlayer: (ExternalPlayerIntentResult.Success) -> Boolean,
    openExternalStreamUrl: (String) -> Boolean,
    onSystemBackHandlerChanged: (PlayerRoute, (() -> Unit)?) -> Unit,
    onOpenPartyMedia: (PartyMedia) -> Unit,
) {
    val launch = remember(route.launchId) { PlayerLaunchStore.get(route.launchId) }
    var activeEpisode by remember(route.launchId, launch) {
        mutableStateOf(
            ActivePlayerEpisode(
                videoId = launch?.videoId,
                seasonNumber = launch?.seasonNumber,
                episodeNumber = launch?.episodeNumber,
                episodeTitle = launch?.episodeTitle,
                episodeThumbnail = launch?.episodeThumbnail,
                pauseDescription = launch?.pauseDescription,
            ),
        )
    }
    val beforePlayerPop = remember(navController) {
        {
            syncUnderlyingStreamRouteOnPlayerExit(
                navController = navController,
                activeEpisode = activeEpisode,
            )
            ResumePromptRepository.markPlayerExitedNormally()
        }
    }
    val popBack = rememberGuardedPopBackStack(
        navController = navController,
        route = route,
        beforePop = beforePlayerPop,
    )
    if (launch == null) {
        LaunchedEffect(route.launchId) {
            popBack()
        }
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    val currentFullscreen = com.nuvio.app.core.ui.isFullscreenActionActive()
    val initialFullscreen = remember { currentFullscreen }
    val onBackBase = rememberGuardedPlayerPopBackStack(
        navController = navController,
        route = route,
        beforePop = beforePlayerPop,
    )
    val onBack = remember(onBackBase, initialFullscreen, currentFullscreen) {
        { releaseBeforeBack: com.nuvio.app.features.player.PlayerReleaseBeforeBack ->
            if (currentFullscreen != initialFullscreen) {
                com.nuvio.app.core.ui.toggleFullscreenAction()
            }
            onBackBase(releaseBeforeBack)
        }
    }
    val registerSystemBack = remember(route, onSystemBackHandlerChanged) {
        { handler: (() -> Unit)? -> onSystemBackHandlerChanged(route, handler) }
    }
    LaunchedEffect(launch.videoId) {
        launch.videoId?.let { ResumePromptRepository.markPlayerEntered(it) }
    }
    PlayerScreen(
        profileId = launch.profileId,
        title = launch.title,
        sourceUrl = launch.sourceUrl,
        sourceAudioUrl = launch.sourceAudioUrl,
        sourceHeaders = launch.sourceHeaders,
        sourceResponseHeaders = launch.sourceResponseHeaders,
        externalSubtitles = launch.externalSubtitles,
        streamType = launch.streamType,
        logo = launch.logo,
        poster = launch.poster,
        background = launch.background,
        seasonNumber = launch.seasonNumber,
        episodeNumber = launch.episodeNumber,
        episodeTitle = launch.episodeTitle,
        episodeThumbnail = launch.episodeThumbnail,
        streamTitle = launch.streamTitle,
        streamSubtitle = launch.streamSubtitle,
        initialBingeGroup = launch.bingeGroup,
        pauseDescription = launch.pauseDescription,
        providerName = launch.providerName,
        providerAddonId = launch.providerAddonId,
        contentType = launch.contentType,
        videoId = launch.videoId,
        parentMetaId = launch.parentMetaId,
        parentMetaType = launch.parentMetaType,
        torrentInfoHash = launch.torrentInfoHash,
        torrentFileIdx = launch.torrentFileIdx,
        torrentFilename = launch.torrentFilename,
        torrentTrackers = launch.torrentTrackers,
        initialPositionMs = launch.initialPositionMs,
        initialProgressFraction = launch.initialProgressFraction,
        contentLanguage = launch.contentLanguage,
        subtitleExtras = launch.subtitleExtras,
        launchId = route.launchId,
        sourceFilename = launch.sourceFilename,
        stream = launch.stream,
        sourceFromLinkCache = launch.sourceFromLinkCache,
        sourceAutoSelected = launch.sourceAutoSelected,
        onActiveEpisodeChanged = { updated -> activeEpisode = updated },
        onBack = onBack,
        onSystemBackHandlerChanged = registerSystemBack,
        onOpenInExternalPlayer = if (externalPlayerSupported) { { request ->
            val playerLaunch = PlayerLaunch(
                profileId = launch.profileId,
                title = launch.title,
                sourceUrl = request.sourceUrl,
                sourceHeaders = request.sourceHeaders,
                logo = launch.logo,
                poster = launch.poster,
                background = launch.background,
                seasonNumber = launch.seasonNumber,
                episodeNumber = launch.episodeNumber,
                episodeTitle = launch.episodeTitle,
                episodeThumbnail = launch.episodeThumbnail,
                streamTitle = request.streamTitle ?: launch.streamTitle,
                streamSubtitle = launch.streamSubtitle,
                bingeGroup = launch.bingeGroup,
                pauseDescription = launch.pauseDescription,
                providerName = launch.providerName,
                providerAddonId = launch.providerAddonId,
                contentType = launch.contentType,
                videoId = launch.videoId,
                parentMetaId = launch.parentMetaId,
                parentMetaType = launch.parentMetaType,
                initialPositionMs = request.resumePositionMs,
                subtitleExtras = launch.subtitleExtras,
            )
            onExternalPlayerLaunch(playerLaunch)
            val intentResult = ExternalPlayerPlatform.buildIntent(
                request = request,
                playerId = externalPlayerId,
            )
            when (intentResult) {
                is ExternalPlayerIntentResult.Success -> {
                    val launched = launchExternalPlayer(intentResult)
                    if (!launched) {
                        NuvioToastController.show(externalPlayerFailedText)
                    } else if (externalPlayerId == "infuse") {
                        popBack()
                    }
                }
                ExternalPlayerIntentResult.NotConfigured -> {
                    NuvioToastController.show(externalPlayerNotConfiguredText)
                }
                ExternalPlayerIntentResult.Failed -> {
                    NuvioToastController.show(externalPlayerFailedText)
                }
            }
        } } else null,
        onOpenExternalUrl = { url ->
            openExternalStreamUrl(url)
        },
        onOpenPartyMedia = onOpenPartyMedia,
        modifier = Modifier.fillMaxSize(),
    )
}
