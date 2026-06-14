/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.test.features

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.test.TestMediampPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VideoAspectRatioIntegrationTest {
    @Test
    fun `test aspect ratio mode changes are reflected in flow`() = runTest {
        val player = TestMediampPlayer()
        val aspectRatio = player.features[VideoAspectRatio.Key]!!

        // Test initial state
        assertEquals(AspectRatioMode.FIT, aspectRatio.mode.value)

        // Test mode changes
        aspectRatio.setMode(AspectRatioMode.STRETCH)
        assertEquals(AspectRatioMode.STRETCH, aspectRatio.mode.first())

        aspectRatio.setMode(AspectRatioMode.CROP)
        assertEquals(AspectRatioMode.CROP, aspectRatio.mode.first())

        aspectRatio.setMode(AspectRatioMode.FIT)
        assertEquals(AspectRatioMode.FIT, aspectRatio.mode.first())
    }

    @Test
    fun `test aspect ratio feature is available on all players`() = runTest {
        val player = TestMediampPlayer()
        val aspectRatio = player.features[VideoAspectRatio.Key]

        assertNotNull(aspectRatio, "VideoAspectRatio feature should be available")
        assertNotNull(aspectRatio.mode, "VideoAspectRatio mode flow should not be null")
    }

    @Test
    fun `test aspect ratio mode enum values`() {
        // Ensure all expected modes are available
        val modes = AspectRatioMode.values()
        assertEquals(3, modes.size)

        val modeNames = modes.map { it.name }.toSet()
        assertEquals(setOf("FIT", "STRETCH", "CROP"), modeNames)
    }
}