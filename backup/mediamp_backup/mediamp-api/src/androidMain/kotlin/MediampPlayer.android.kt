/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.file.Path


/**
 * Plays the video at the specified [uri].
 */
public suspend inline fun MediampPlayer.playUri(uri: Uri): Unit =
    playUri(uri.toString())


/**
 * Plays the file.
 */
@RequiresApi(Build.VERSION_CODES.O)
public suspend inline fun MediampPlayer.playFile(file: Path): Unit =
    playUri(file.toUri().toString())
