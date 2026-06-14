/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.features

/**
 * An optional feature of the [org.openani.mediamp.MediampPlayer].
 *
 * Instances can be obtained using [PlayerFeatures.get] from the [org.openani.mediamp.MediampPlayer.features].
 */
public interface Feature

/**
 * A typed key for a feature. It is designed to be used with [PlayerFeatures.get] so that no casting or reflection is needed.
 */
public interface FeatureKey<@Suppress("unused") F : Feature>
