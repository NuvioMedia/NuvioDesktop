/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer

import android.content.Context
import org.openani.mediamp.MediampPlayerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

class ExoPlayerMediampPlayerFactory : MediampPlayerFactory<ExoPlayerMediampPlayer> {
    override val forClass: KClass<ExoPlayerMediampPlayer> get() = ExoPlayerMediampPlayer::class

    override fun create(
        context: Any,
        parentCoroutineContext: CoroutineContext,
    ): ExoPlayerMediampPlayer {
        require(context is Context) { "The context argument must be android.content.Context on Android" }
        return create(context, parentCoroutineContext)
    }

    fun create(
        context: Context,
        parentCoroutineContext: CoroutineContext,
    ): ExoPlayerMediampPlayer {
        return ExoPlayerMediampPlayer(context, parentCoroutineContext)
    }
}
