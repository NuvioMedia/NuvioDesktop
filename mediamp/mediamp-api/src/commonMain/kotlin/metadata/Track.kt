/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.metadata

import org.openani.mediamp.InternalMediampApi

public sealed interface Track

public class SubtitleTrack @InternalMediampApi constructor(
    public val id: String,
    public val internalId: String,
    public val language: String?,
    public val labels: List<TrackLabel>,
) : Track

public class AudioTrack @InternalMediampApi constructor(
    public val id: String,
    public val internalId: String,
    public val name: String?,
    public val labels: List<TrackLabel>,
) : Track

public class TrackLabel @InternalMediampApi constructor(
    public val language: String?, // "zh" 这指的是 value 的语言
    public val value: String // "CHS", "简日", "繁日"
)
