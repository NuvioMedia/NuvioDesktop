/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.avkit

import org.openani.mediamp.MediampPlayerFactory
import org.openani.mediamp.MediampPlayerFactoryLoader
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

public class AVKitMediampPlayerFactory : MediampPlayerFactory<AVKitMediampPlayer> {
    override val forClass: KClass<AVKitMediampPlayer> = AVKitMediampPlayer::class

    override fun create(
        context: Any,
        parentCoroutineContext: CoroutineContext
    ): AVKitMediampPlayer {
        // TODO: 2025/3/30 use parentCoroutineContext
        return AVKitMediampPlayer(parentCoroutineContext)
    }
}

@Suppress("DEPRECATION", "ObjectPropertyName", "unused")
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val _register = MediampPlayerFactoryLoader.register(AVKitMediampPlayerFactory())
