/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.compose

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.openani.mediamp.exoplayer.ExoPlayerMediampPlayer
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.VideoAspectRatio

@OptIn(UnstableApi::class)
@Composable
fun ExoPlayerMediampPlayerSurface(
    mediampPlayer: ExoPlayerMediampPlayer,
    modifier: Modifier = Modifier,
    configuration: PlayerView.() -> Unit = {},
) {
    val aspectRatioMode by mediampPlayer.features[VideoAspectRatio.Key]?.mode?.collectAsState() 
        ?: return // Return early if VideoAspectRatio feature is not available
    
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                this.player = mediampPlayer.impl
                configuration()
            }
        },
        modifier,
        onRelease = {
        },
        update = { view ->
            view.player = mediampPlayer.impl
            // Apply aspect ratio mode to PlayerView
            view.resizeMode = when (aspectRatioMode) {
                AspectRatioMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectRatioMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectRatioMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
    )
}
