package com.nuvio.app.features.player.desktop.mpv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface

@Composable
internal fun MpvDesktopPlayerSurface(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    MpvMediampPlayerSurface(
        player = player,
        modifier = modifier,
    )
}
