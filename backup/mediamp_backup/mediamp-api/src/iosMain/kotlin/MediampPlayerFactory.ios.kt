/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import org.openani.mediamp.internal.ConcurrentRegistryManager
import kotlin.coroutines.CoroutineContext

public actual fun MediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext,
): MediampPlayer = MediampPlayerFactoryLoader.first()
    .create(context, parentCoroutineContext)

public object MediampPlayerFactoryLoader {
    private var factories = ConcurrentRegistryManager<MediampPlayerFactory<*>>()

    /**
     * Register a [MediampPlayerFactory] implementation.
     */
    public fun register(factory: MediampPlayerFactory<*>) {
        factories.append(factory)
    }

    public fun first(): MediampPlayerFactory<*> = factories.firstOrNull()
        ?: throw IllegalStateException("No MediampPlayerFactory implementation found on the classpath.")

    public fun getByInstance(mediampPlayer: MediampPlayer): MediampPlayerFactory<*> = factories.find {
        it.forClass.isInstance(mediampPlayer)
    } ?: throw IllegalStateException("No MediampPlayerFactory implementation found for $mediampPlayer.")
}
