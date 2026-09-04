package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSurfaceLifetimeTest {
    @Test
    fun staleReleaseCallbackCannotClearCurrentAttempt() {
        val retention = PlayerReleaseSurfaceRetention()
        val staleAttempt = retention.begin()
        assertTrue(retention.finish(staleAttempt))
        val currentAttempt = retention.begin()

        assertFalse(retention.finish(staleAttempt))
        assertTrue(retention.inFlight)
        assertTrue(retention.finish(currentAttempt))
        assertFalse(retention.inFlight)
    }

    @Test
    fun duplicateReleaseCallbackIsInert() {
        val retention = PlayerReleaseSurfaceRetention()
        val attempt = retention.begin()

        assertTrue(retention.finish(attempt))
        assertFalse(retention.finish(attempt))
        assertFalse(retention.inFlight)
    }

    @Test
    fun ordinaryDesktopSourceGapUnmountsSurface() {
        assertFalse(
            shouldRenderPlayerSurface(
                hasCurrentSource = false,
                hasLifecycleController = true,
                releaseInFlight = false,
                desktop = true,
            ),
        )
    }

    @Test
    fun desktopKeepsNativeHostWhileNavigationReleaseIsInFlight() {
        assertTrue(
            shouldRenderPlayerSurface(
                hasCurrentSource = false,
                hasLifecycleController = true,
                releaseInFlight = true,
                desktop = true,
            ),
        )
    }

    @Test
    fun releaseWithoutLifecycleControllerDoesNotCreateSurface() {
        assertFalse(
            shouldRenderPlayerSurface(
                hasCurrentSource = false,
                hasLifecycleController = false,
                releaseInFlight = true,
                desktop = true,
            ),
        )
    }

    @Test
    fun nonDesktopReleaseDoesNotRetainSurface() {
        assertFalse(
            shouldRenderPlayerSurface(
                hasCurrentSource = false,
                hasLifecycleController = true,
                releaseInFlight = true,
                desktop = false,
            ),
        )
    }

    @Test
    fun currentSourceAlwaysRendersSurface() {
        assertTrue(
            shouldRenderPlayerSurface(
                hasCurrentSource = true,
                hasLifecycleController = false,
                releaseInFlight = false,
                desktop = false,
            ),
        )
    }
}
