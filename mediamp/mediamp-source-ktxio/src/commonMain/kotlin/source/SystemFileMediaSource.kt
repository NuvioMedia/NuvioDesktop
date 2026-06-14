/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.io.SystemFileSeekableInput
import kotlin.coroutines.CoroutineContext

/**
 * A [MediaData] that represents a media that is backed by a system file.
 */
@OptIn(ExperimentalMediampApi::class)
public sealed interface SystemFileMediaData : SeekableInputMediaData {

    /**
     * The file that backs this media data in [SystemFileSystem].
     */
    public val file: Path
}


@OptIn(ExperimentalMediampApi::class)
internal class SystemFileMediaDataImpl(
    override val file: Path,
    override val extraFiles: MediaExtraFiles,
    override val uri: String,
    override val options: List<String>,
    private val bufferSize: Int = 8 * 1024,
) : SystemFileMediaData {
    @Throws(IOException::class)
    override fun fileLength(): Long? = SystemFileSystem.metadataOrNull(file)?.size

    override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput =
        withContext(Dispatchers.IO) { SystemFileSeekableInput(file, bufferSize) }

    override fun close() {}
}

/**
 * Creates a [SystemFileMediaData] from the given [path].
 *
 * @throws FileNotFoundException if the file does not exist.
 */
@Throws(FileNotFoundException::class)
public fun SystemFileMediaData(
    path: Path,
    extraFiles: MediaExtraFiles = MediaExtraFiles.EMPTY,
    options: List<String> = emptyList(),
): SystemFileMediaData {
    val resolve = SystemFileSystem.resolve(path)
    return SystemFileMediaDataImpl(
        path,
        extraFiles,
        uri = "file://$resolve",
        options,
    )
}
