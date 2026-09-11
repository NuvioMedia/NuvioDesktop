package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlEpisodeItem

// Desktop-only MPRIS helper. Mirrors the shared episode ordering
// (PlayerNextEpisodeRules: sort by season, then episode, resolve current,
// take the previous logical item) without touching commonMain.
internal fun List<PlayerControlEpisodeItem>.resolvePreviousEpisode(): PlayerControlEpisodeItem? {
    val current = firstOrNull { it.isCurrent } ?: return null
    val ordered = sortedWith(compareBy<PlayerControlEpisodeItem> { it.season }.thenBy { it.episode })
    val currentIndex = ordered.indexOfFirst {
        it.season == current.season && it.episode == current.episode
    }
    if (currentIndex <= 0) return null
    return ordered.getOrNull(currentIndex - 1)
}

// Desktop-only MPRIS Previous command path. Restart above 5s, otherwise play
// the logically previous episode (same ordering as CanGoPrevious above).
internal sealed interface MprisPreviousAction {
    data object RestartCurrent : MprisPreviousAction
    data class PlayAtIndex(val index: Int) : MprisPreviousAction
    data object NoPrevious : MprisPreviousAction
}

internal fun resolveMprisPreviousAction(
    positionMs: Long,
    episodeItems: List<PlayerControlEpisodeItem>,
): MprisPreviousAction {
    val previous = episodeItems.resolvePreviousEpisode() ?: return MprisPreviousAction.NoPrevious
    return if (positionMs > 5_000L) MprisPreviousAction.RestartCurrent
    else MprisPreviousAction.PlayAtIndex(previous.index)
}
