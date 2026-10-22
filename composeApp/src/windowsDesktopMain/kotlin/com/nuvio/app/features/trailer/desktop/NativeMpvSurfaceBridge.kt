package com.nuvio.app.features.trailer.desktop

import com.nuvio.app.features.player.desktop.NativePlayerBridge

internal object NativeMpvSurfaceBridge {
    init {
        // Triggers static initialization of NativePlayerBridge, ensuring player_bridge.dll and dependencies are loaded
        checkNotNull(NativePlayerBridge)
    }

    external fun nativeCreate(
        videoUrl: String,
        audioUrl: String?,
        startPositionMs: Long,
        playWhenReady: Boolean,
        muted: Boolean,
        fillFrame: Boolean,
    ): Long

    external fun nativeRenderFrame(
        handle: Long,
        pixelsAddr: Long,
        width: Int,
        height: Int,
    ): Boolean

    external fun nativeSetMuted(handle: Long, muted: Boolean)

    external fun nativeSetPaused(handle: Long, paused: Boolean)

    external fun nativeIsReady(handle: Long): Boolean

    external fun nativeIsEnded(handle: Long): Boolean

    external fun nativeHasError(handle: Long): Boolean

    external fun nativeDispose(handle: Long)
}
