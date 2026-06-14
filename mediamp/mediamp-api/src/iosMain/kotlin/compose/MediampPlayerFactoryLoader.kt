/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.compose

import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.internal.ConcurrentRegistryManager

public object MediampPlayerSurfaceProviderLoader {
    private var factories = ConcurrentRegistryManager<MediampPlayerSurfaceProvider<*>>()

    /**
     * Register a [MediampPlayerSurfaceProvider] implementation.
     */
    public fun register(factory: MediampPlayerSurfaceProvider<*>) {
        factories.append(factory)
    }

    public fun first(): MediampPlayerSurfaceProvider<*> = factories.firstOrNull()
        ?: throw IllegalStateException("No MediampPlayerFactory implementation found on the classpath.")

    public fun <T : MediampPlayer> getByInstance(mediampPlayer: T): MediampPlayerSurfaceProvider<T> {
        val res = factories.find {
            it.forClass.isInstance(mediampPlayer)
        } ?: throw IllegalStateException("No MediampPlayerFactory implementation found for $mediampPlayer.")

        @Suppress("UNCHECKED_CAST")
        return res as MediampPlayerSurfaceProvider<T>
    }
}
