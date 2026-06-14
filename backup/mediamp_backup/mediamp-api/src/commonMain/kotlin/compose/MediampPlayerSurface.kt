/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import org.openani.mediamp.MediampPlayer
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Displays the media content (e.g. a video) of [org.openani.mediamp.MediampPlayer].
 *
 * There is no control bar or any other UI elements, it's instead, similar to a [androidx.compose.ui.graphics.Canvas].
 *
 * The view takes all available space by default ([Modifier.fillMaxSize]).
 * You can pass an appropriate size [Modifier] to control the size of the player.
 *
 * The surface is center-aligned, fitting the available space while maintaining the aspect ratio.
 */
@Composable
public expect fun MediampPlayerSurface(
    mediampPlayer: MediampPlayer,
    modifier: Modifier = Modifier,
)

/**
 * Remembers a [MediampPlayer] instance that will be stopped when the composable is no longer in the composition.
 */
@Composable
public expect fun rememberMediampPlayer(parentCoroutineContext: () -> CoroutineContext = { EmptyCoroutineContext }): MediampPlayer


@Stable
internal class RememberedMediampPlayer(val player: MediampPlayer) : RememberObserver {
    override fun onAbandoned() {
        player.close()
    }

    override fun onForgotten() {
        player.close()
    }

    override fun onRemembered() {
    }
}
