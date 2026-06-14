/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.avkit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.openani.mediamp.compose.MediampPlayerSurfaceProvider
import org.openani.mediamp.compose.MediampPlayerSurfaceProviderLoader
import kotlin.reflect.KClass

public class AVKitMediampPlayerSurfaceProvider : MediampPlayerSurfaceProvider<AVKitMediampPlayer> {
    override val forClass: KClass<AVKitMediampPlayer> = AVKitMediampPlayer::class

    @Composable
    override fun Surface(mediampPlayer: AVKitMediampPlayer, modifier: Modifier) {
        AVKitMediampPlayerSurface(mediampPlayer, modifier)
    }
}


@Suppress("DEPRECATION", "ObjectPropertyName", "unused")
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val _register = MediampPlayerSurfaceProviderLoader.register(AVKitMediampPlayerSurfaceProvider())
