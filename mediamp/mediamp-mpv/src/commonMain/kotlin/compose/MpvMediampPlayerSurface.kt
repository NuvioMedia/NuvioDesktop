/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:Suppress("INVISIBLE_MEMBER")

package org.openani.mediamp.mpv.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.openani.mediamp.mpv.MpvMediampPlayer

@Composable
expect fun MpvMediampPlayerSurface(
    player: MpvMediampPlayer,
    modifier: Modifier = Modifier,
)

