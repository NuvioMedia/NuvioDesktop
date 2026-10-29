package com.nuvio.app.features.player

import kotlinx.coroutines.delay

private const val CHAPTER_RETRIES = 8

/**
 * Reads the chapters once per source, when it has loaded: libmpv lists them itself, ExoPlayer
 * reads them from the file (when allowed), so a rebuffer must not read them again.
 */
internal suspend fun PlayerScreenRuntime.loadChaptersIfNeeded() {
    val controller = playerController ?: return
    val sourceUrl = playerControllerSourceUrl ?: return
    if (chaptersLoadedForSourceUrl == sourceUrl) return
    var loaded = PlayerChapters.normalize(controller.getChapters())
    var attempts = 0
    while (loaded.isEmpty() && attempts < CHAPTER_RETRIES) {
        delay(1_000)
        if (playerControllerSourceUrl != sourceUrl || playerController !== controller) return
        loaded = PlayerChapters.normalize(controller.getChapters())
        attempts++
    }
    if (playerControllerSourceUrl != sourceUrl || playerController !== controller) return
    chaptersLoadedForSourceUrl = sourceUrl
    chapters = loaded
}

internal fun PlayerScreenRuntime.openChaptersPanel() {
    if (chapters.isEmpty()) return
    showChaptersPanel = true
    showSourcesPanel = false
    showEpisodesPanel = false
    controlsVisible = false
}

internal fun PlayerScreenRuntime.selectChapter(index: Int) {
    val chapter = chapters.getOrNull(index) ?: return
    showChaptersPanel = false
    lastManualSkipSeekPositions = playbackSnapshot.positionMs to chapter.startMs
    playerController?.seekTo(chapter.startMs)
    scheduleProgressSyncAfterSeek()
    controlsVisible = true
}
