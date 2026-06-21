package com.nuvio.app.features.player.desktop

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class NativePlayerHost : PlayerHost {
    @Volatile
    override var nativeHandle: Long = 0L

    override var onMouseClick: (() -> Unit)? = null
    override var onCursorActivity: (() -> Unit)? = null

    private var pixelBuffer: IntArray? = null
    private var pixelBytes: ByteArray? = null
    private var gcCounter = 0

    var latestImage: Image? = null
        private set

    fun renderFrame(width: Int, height: Int): Boolean {
        val handle = nativeHandle
        if (handle == 0L || width <= 0 || height <= 0) return false

        val count = width * height
        val byteCount = count * 4

        val pix = pixelBuffer?.takeIf { it.size >= count }
            ?: IntArray(count).also { pixelBuffer = it }

        if (!NativePlayerBridge.renderFrame(handle, pix, width, height)) return false

        val bytes = pixelBytes?.takeIf { it.size >= byteCount }
            ?: ByteArray(byteCount).also { pixelBytes = it }

        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pix, 0, count)

        val imageInfo = ImageInfo.makeS32(width, height, ColorAlphaType.UNPREMUL)
        val previousImage = latestImage
        latestImage = Image.makeRaster(imageInfo, bytes, width * 4)
        previousImage?.close()

        if (++gcCounter % 60 == 0) System.gc()
        return true
    }

    override fun dispose() {
        latestImage?.close()
        latestImage = null
        pixelBuffer = null
        pixelBytes = null
    }

    override fun setControlsVisible(visible: Boolean) {}
    override fun noteCursorActivity() {}
    override fun resetCursorVisibility() {}
}