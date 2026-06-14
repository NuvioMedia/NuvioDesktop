/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.io

import kotlinx.io.files.Path

/**
 * Opens a [SeekableInput] for the specified [path] using the [kotlinx.io.files.SystemFileSystem].
 * @param onFillBuffer a callback that is invoked when the buffer is empty and needs to be filled.
 */
@Suppress("FunctionName")
public expect fun SystemFileSeekableInput(
    path: Path,
    bufferSize: Int = 8 * 1024,
    onFillBuffer: (() -> Unit)? = null,
): SeekableInput
