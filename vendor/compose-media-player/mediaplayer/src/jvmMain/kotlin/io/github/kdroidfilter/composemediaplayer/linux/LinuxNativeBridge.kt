package io.github.kdroidfilter.composemediaplayer.linux

import io.github.kdroidfilter.composemediaplayer.util.NativeLibraryLoader
import java.nio.ByteBuffer

/**
 * JNI direct mapping to the native Linux GStreamer video player library.
 * Handles are opaque Long values (native pointer cast to jlong, 0 = null).
 */
internal object LinuxNativeBridge {
    const val FRAME_COPY_INVALID = -1
    const val FRAME_COPY_DEST_TOO_SMALL = -2
    const val FRAME_COPY_SIZE_CHANGED = -3
    const val FRAME_COPY_NOT_READY = 0
    const val FRAME_COPY_OK = 1

    init {
        NativeLibraryLoader.load("NativeVideoPlayer", LinuxNativeBridge::class.java)
    }

    // Playback control
    @JvmStatic external fun nCreatePlayer(): Long

    @JvmStatic external fun nOpenUri(
        handle: Long,
        uri: String,
    )

    @JvmStatic external fun nPlay(handle: Long)

    @JvmStatic external fun nPause(handle: Long)

    @JvmStatic external fun nSetVolume(
        handle: Long,
        volume: Float,
    )

    @JvmStatic external fun nGetVolume(handle: Long): Float

    @JvmStatic external fun nSeekTo(
        handle: Long,
        time: Double,
    )

    @JvmStatic external fun nDisposePlayer(handle: Long)

    @JvmStatic external fun nSetPlaybackSpeed(
        handle: Long,
        speed: Float,
    )

    @JvmStatic external fun nGetPlaybackSpeed(handle: Long): Float

    // Frame access
    @JvmStatic external fun nCopyLatestFrame(
        handle: Long,
        destination: ByteBuffer,
        expectedWidth: Int,
        expectedHeight: Int,
        destinationStride: Int,
        outInfo: IntArray,
    ): Int

    @JvmStatic external fun nWrapPointer(
        address: Long,
        size: Long,
    ): ByteBuffer?

    @JvmStatic external fun nGetFrameWidth(handle: Long): Int

    @JvmStatic external fun nGetFrameHeight(handle: Long): Int

    @JvmStatic external fun nSetOutputSize(
        handle: Long,
        width: Int,
        height: Int,
    ): Int

    // Timing
    @JvmStatic external fun nGetVideoDuration(handle: Long): Double

    @JvmStatic external fun nGetCurrentTime(handle: Long): Double

    // Metadata
    @JvmStatic external fun nGetVideoTitle(handle: Long): String?

    @JvmStatic external fun nGetVideoBitrate(handle: Long): Long

    @JvmStatic external fun nGetVideoMimeType(handle: Long): String?

    @JvmStatic external fun nGetAudioChannels(handle: Long): Int

    @JvmStatic external fun nGetAudioSampleRate(handle: Long): Int

    @JvmStatic external fun nGetFrameRate(handle: Long): Float

    // Playback completion
    @JvmStatic external fun nConsumeDidPlayToEnd(handle: Long): Boolean
}
