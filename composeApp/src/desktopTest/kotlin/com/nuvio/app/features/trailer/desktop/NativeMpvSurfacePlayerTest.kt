package com.nuvio.app.features.trailer.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeMpvSurfacePlayerTest {
    @Test
    fun testNativeBridgeHandleSafety() {
        // Accessing NativeMpvSurfaceBridge ensures player_bridge.dll loads successfully
        val isReady = NativeMpvSurfaceBridge.nativeIsReady(0L)
        assertFalse(isReady)

        val isEnded = NativeMpvSurfaceBridge.nativeIsEnded(0L)
        assertTrue(isEnded)

        val hasError = NativeMpvSurfaceBridge.nativeHasError(0L)
        assertTrue(hasError)

        val rendered = NativeMpvSurfaceBridge.nativeRenderFrame(0L, 0L, 0, 0)
        assertFalse(rendered)

        // Verify dispose with null handle does not crash
        NativeMpvSurfaceBridge.nativeDispose(0L)
    }

    @Test
    fun testNativeMpvSurfacePlayerLifecycle() {
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val player = NativeMpvSurfacePlayer(
            videoUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            audioUrl = null,
            startPositionMillis = 0L,
            playWhenReady = false,
            initialMuted = true,
            scope = testScope,
            onReady = {},
            onEnded = {},
            onError = {},
        )

        player.setSize(640, 360)
        player.setMuted(false)
        player.setPaused(true)
        player.dispose()
    }
}
