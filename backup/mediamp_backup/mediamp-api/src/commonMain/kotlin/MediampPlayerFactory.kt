/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass

/**
 * Factory interface for creating a [MediampPlayer].
 */
public interface MediampPlayerFactory<T : MediampPlayer> { // SPI load on JVM
    public val forClass: KClass<T>

    /**
     * Creates a new [MediampPlayer].
     *
     * @param context the platform context to create the underlying player implementation.
     * It is only used by the constructor and not stored. On Android, this must be the `android.content.Context`. On other platforms, this is ignored so it can be any object.
     * @param parentCoroutineContext can pass in a [kotlinx.coroutines.Job] so that the player state is bound to the parent coroutine context scope.
     */
    public fun create(
        context: Any, // Not introducing an expect/actual because this will instead cause complexity
        parentCoroutineContext: CoroutineContext = EmptyCoroutineContext
    ): T
}

/**
 * Creates a new [MediampPlayer] using the first [MediampPlayerFactory] implementation found on the classpath.
 */
public expect fun MediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext
): MediampPlayer
