package io.github.kdroidfilter.composemediaplayer.linux

import java.nio.ByteBuffer

/** Injectable JNI seam; production delegates one-for-one to [LinuxNativeBridge]. */
internal interface LinuxPlayerBridge {
    fun createPlayer(): Long

    fun openUri(
        handle: Long,
        uri: String,
    )

    fun play(handle: Long)

    fun pause(handle: Long)

    fun setVolume(
        handle: Long,
        volume: Float,
    )

    fun seekTo(
        handle: Long,
        time: Double,
    )

    fun disposePlayer(handle: Long)

    fun setPlaybackSpeed(
        handle: Long,
        speed: Float,
    )

    fun copyLatestFrame(
        handle: Long,
        destination: ByteBuffer,
        expectedWidth: Int,
        expectedHeight: Int,
        destinationStride: Int,
        outInfo: IntArray,
    ): Int

    fun wrapPointer(
        address: Long,
        size: Long,
    ): ByteBuffer?

    fun frameWidth(handle: Long): Int

    fun frameHeight(handle: Long): Int

    fun setOutputSize(
        handle: Long,
        width: Int,
        height: Int,
    ): Int

    fun videoDuration(handle: Long): Double

    fun currentTime(handle: Long): Double

    fun videoTitle(handle: Long): String?

    fun videoBitrate(handle: Long): Long

    fun videoMimeType(handle: Long): String?

    fun audioChannels(handle: Long): Int

    fun audioSampleRate(handle: Long): Int

    fun frameRate(handle: Long): Float

    fun consumeDidPlayToEnd(handle: Long): Boolean
}

internal object JniLinuxPlayerBridge : LinuxPlayerBridge {
    override fun createPlayer() = LinuxNativeBridge.nCreatePlayer()

    override fun openUri(
        handle: Long,
        uri: String,
    ) = LinuxNativeBridge.nOpenUri(handle, uri)

    override fun play(handle: Long) = LinuxNativeBridge.nPlay(handle)

    override fun pause(handle: Long) = LinuxNativeBridge.nPause(handle)

    override fun setVolume(
        handle: Long,
        volume: Float,
    ) = LinuxNativeBridge.nSetVolume(handle, volume)

    override fun seekTo(
        handle: Long,
        time: Double,
    ) = LinuxNativeBridge.nSeekTo(handle, time)

    override fun disposePlayer(handle: Long) = LinuxNativeBridge.nDisposePlayer(handle)

    override fun setPlaybackSpeed(
        handle: Long,
        speed: Float,
    ) = LinuxNativeBridge.nSetPlaybackSpeed(handle, speed)

    override fun copyLatestFrame(
        handle: Long,
        destination: ByteBuffer,
        expectedWidth: Int,
        expectedHeight: Int,
        destinationStride: Int,
        outInfo: IntArray,
    ) = LinuxNativeBridge.nCopyLatestFrame(
        handle,
        destination,
        expectedWidth,
        expectedHeight,
        destinationStride,
        outInfo,
    )

    override fun wrapPointer(
        address: Long,
        size: Long,
    ) = LinuxNativeBridge.nWrapPointer(address, size)

    override fun frameWidth(handle: Long) = LinuxNativeBridge.nGetFrameWidth(handle)

    override fun frameHeight(handle: Long) = LinuxNativeBridge.nGetFrameHeight(handle)

    override fun setOutputSize(
        handle: Long,
        width: Int,
        height: Int,
    ) = LinuxNativeBridge.nSetOutputSize(handle, width, height)

    override fun videoDuration(handle: Long) = LinuxNativeBridge.nGetVideoDuration(handle)

    override fun currentTime(handle: Long) = LinuxNativeBridge.nGetCurrentTime(handle)

    override fun videoTitle(handle: Long) = LinuxNativeBridge.nGetVideoTitle(handle)

    override fun videoBitrate(handle: Long) = LinuxNativeBridge.nGetVideoBitrate(handle)

    override fun videoMimeType(handle: Long) = LinuxNativeBridge.nGetVideoMimeType(handle)

    override fun audioChannels(handle: Long) = LinuxNativeBridge.nGetAudioChannels(handle)

    override fun audioSampleRate(handle: Long) = LinuxNativeBridge.nGetAudioSampleRate(handle)

    override fun frameRate(handle: Long) = LinuxNativeBridge.nGetFrameRate(handle)

    override fun consumeDidPlayToEnd(handle: Long) = LinuxNativeBridge.nConsumeDidPlayToEnd(handle)
}
