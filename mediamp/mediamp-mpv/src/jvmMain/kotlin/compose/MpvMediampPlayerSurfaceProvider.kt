/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.openani.mediamp.compose.MediampPlayerSurfaceProvider
import org.openani.mediamp.mpv.MpvMediampPlayer
import kotlin.reflect.KClass

public class MpvMediampPlayerSurfaceProvider : MediampPlayerSurfaceProvider<MpvMediampPlayer> {
    override val forClass: KClass<MpvMediampPlayer> = MpvMediampPlayer::class

    @Composable
    override fun Surface(mediampPlayer: MpvMediampPlayer, modifier: Modifier) {
        MpvMediampPlayerSurface(mediampPlayer, modifier)
    }
}
