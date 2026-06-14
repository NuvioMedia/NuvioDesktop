/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.source

import kotlin.jvm.JvmField

public class MediaExtraFiles(
    public val subtitles: List<Subtitle> = emptyList(),
) {
    public companion object {
        @JvmField
        public val EMPTY: MediaExtraFiles = MediaExtraFiles()
    }
}

public class Subtitle(
    /**
     * e.g. `https://example.com/1.ass`
     */
    public val uri: String,
    public val mimeType: String? = null,
    public val language: String? = null,
    public val label: String? = null,
)
