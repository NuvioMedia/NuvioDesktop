/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.source

import org.openani.mediamp.ExperimentalMediampApi

/**
 * A [MediaData] that represents a media that can be fetched from a URI, typically a streaming media.
 */
public sealed interface UriMediaData : MediaData {
    /**
     * The URI of the media.
     */
    public val uri: String

    /**
     * The headers to be used when fetching the media.
     *
     * Note that whether the headers are used or not is implementation-dependent:
     * - ExoPlayer uses all the headers
     * - VLC only uses the `User-Agent` and `Referer`.
     *
     * Keys are **case-sensitive**. `UserAgent` may NOT be used. Using `User-Agent` and `Referer` are recommended.
     */
    public val headers: Map<String, String>
}


/**
 * Create a [UriMediaData] instance.
 *
 * @param uri The URI of the media. For example `https://example.com/video.mp4` or `file:///sdcard/video.mp4`.
 */
public fun UriMediaData(
    uri: String,
    headers: Map<String, String> = emptyMap(),
    extraFiles: MediaExtraFiles = MediaExtraFiles.EMPTY,
): UriMediaData = UriMediaDataImpl(uri, headers, extraFiles)

/**
 * Create a [UriMediaData] instance.
 *
 * @param uri The URI of the media. For example `https://example.com/video.mp4` or `file:///sdcard/video.mp4`.
 */
@OptIn(ExperimentalMediampApi::class)
public fun UriMediaData(
    uri: String,
    headers: Map<String, String> = emptyMap(),
    extraFiles: MediaExtraFiles = MediaExtraFiles.EMPTY,
    options: List<String> = emptyList(),
): UriMediaData = UriMediaDataImpl(uri, headers, extraFiles, options)


internal class UriMediaDataImpl @ExperimentalMediampApi constructor(
    override val uri: String,
    override val headers: Map<String, String>,
    override val extraFiles: MediaExtraFiles,
    @property:ExperimentalMediampApi
    override val options: List<String> = emptyList(),
) : MediaData, UriMediaData {
    constructor(
        uri: String,
        headers: Map<String, String>,
        extraFiles: MediaExtraFiles,
    ) : this(uri, headers, extraFiles, emptyList())

    override fun close() {}

    override fun toString(): String {
        return "UriMediaData(uri=$uri, headers=$headers, extraFiles=$extraFiles)"
    }
}
