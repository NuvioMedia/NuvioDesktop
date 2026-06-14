/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.openani.mediamp.MediampPlayer
import kotlin.coroutines.CoroutineContext

@Composable
public actual fun rememberMediampPlayer(parentCoroutineContext: () -> CoroutineContext): MediampPlayer {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        RememberedMediampPlayer(MediampPlayer(context, parentCoroutineContext()))
    }.player
}
