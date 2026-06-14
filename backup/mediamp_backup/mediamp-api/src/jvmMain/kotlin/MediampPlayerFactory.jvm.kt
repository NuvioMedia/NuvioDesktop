/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package org.openani.mediamp

import java.util.ServiceLoader
import kotlin.coroutines.CoroutineContext

public actual fun MediampPlayer(
    context: Any,
    parentCoroutineContext: CoroutineContext,
): MediampPlayer = MediampPlayerFactoryLoader.first()
    .create(context, parentCoroutineContext)

public object MediampPlayerFactoryLoader {
    private var factories = ServiceLoader.load(MediampPlayerFactory::class.java).toList()

    /**
     * Register a [MediampPlayerFactory] implementation.
     */
    public fun register(factory: MediampPlayerFactory<*>) {
        factories = (factories + factory).distinctBy { it.forClass }
    }

    public fun first(): MediampPlayerFactory<*> = factories.firstOrNull()
        ?: throw IllegalStateException("No MediampPlayerFactory implementation found on the classpath.")

    public fun getByInstance(mediampPlayer: MediampPlayer): MediampPlayerFactory<*> = factories.find {
        it.forClass.isInstance(mediampPlayer)
    } ?: throw IllegalStateException("No MediampPlayerFactory implementation found for $mediampPlayer.")
}
