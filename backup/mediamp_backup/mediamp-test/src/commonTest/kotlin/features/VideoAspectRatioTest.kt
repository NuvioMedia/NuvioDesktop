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

class VideoAspectRatioTest {
    @Test
    fun `test default aspect ratio mode is FIT`() = runTest {
        val player = TestMediampPlayer()
        val aspectRatio = player.features[VideoAspectRatio.Key]

        assertNotNull(aspectRatio)
        assertEquals(AspectRatioMode.FIT, aspectRatio.mode.value)
    }

    @Test
    fun `test setting aspect ratio mode`() = runTest {
        val player = TestMediampPlayer()
        val aspectRatio = player.features[VideoAspectRatio.Key]!!

        aspectRatio.setMode(AspectRatioMode.STRETCH)
        assertEquals(AspectRatioMode.STRETCH, aspectRatio.mode.first())

        aspectRatio.setMode(AspectRatioMode.CROP)
        assertEquals(AspectRatioMode.CROP, aspectRatio.mode.first())

        aspectRatio.setMode(AspectRatioMode.FIT)
        assertEquals(AspectRatioMode.FIT, aspectRatio.mode.first())
    }

    @Test
    fun `test aspect ratio mode flow updates`() = runTest {
        val player = TestMediampPlayer()
        val aspectRatio = player.features[VideoAspectRatio.Key]!!

        var receivedMode: AspectRatioMode? = null

        // Collect the first emission
        receivedMode = aspectRatio.mode.first()
        assertEquals(AspectRatioMode.FIT, receivedMode)

        // Change mode and verify
        aspectRatio.setMode(AspectRatioMode.STRETCH)
        receivedMode = aspectRatio.mode.first()
        assertEquals(AspectRatioMode.STRETCH, receivedMode)
    }
}