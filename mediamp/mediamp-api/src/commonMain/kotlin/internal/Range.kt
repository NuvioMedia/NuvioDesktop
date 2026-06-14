/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(ExperimentalMultiplatform::class)

package org.openani.mediamp.internal

/**
 * Jetbrains Annotations range
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.TYPE)
@OptionalExpectation
internal expect annotation class Range(
    /**
     * @return minimal allowed value (inclusive)
     */
    val from: Long,
    /*
     * @return maximal allowed value (inclusive)
     */
    val to: Long
)
