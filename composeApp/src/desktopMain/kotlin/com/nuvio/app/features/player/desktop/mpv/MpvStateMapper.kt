package com.nuvio.app.features.player.desktop.mpv

import com.nuvio.app.features.player.desktop.DesktopPlayerPhase
import org.openani.mediamp.PlaybackState

internal fun PlaybackState.toDesktopPhase(): DesktopPlayerPhase =
    when (this) {
        PlaybackState.DESTROYED -> DesktopPlayerPhase.Closed
        PlaybackState.ERROR -> DesktopPlayerPhase.Error
        PlaybackState.CREATED -> DesktopPlayerPhase.Idle
        PlaybackState.FINISHED -> DesktopPlayerPhase.Ended
        PlaybackState.READY -> DesktopPlayerPhase.Ready
        PlaybackState.PAUSED -> DesktopPlayerPhase.Paused
        PlaybackState.PLAYING -> DesktopPlayerPhase.Playing
        PlaybackState.PAUSED_BUFFERING -> DesktopPlayerPhase.Buffering
    }
