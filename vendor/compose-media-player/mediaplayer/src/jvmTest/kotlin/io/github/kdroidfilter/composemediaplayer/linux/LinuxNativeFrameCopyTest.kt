package io.github.kdroidfilter.composemediaplayer.linux

import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform
import org.junit.Assume
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LinuxNativeFrameCopyTest {
    private fun twoByTwoBmp(): ByteArray =
        ByteBuffer
            .allocate(70)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put('B'.code.toByte())
                put('M'.code.toByte())
                putInt(70)
                putShort(0)
                putShort(0)
                putInt(54)
                putInt(40)
                putInt(2)
                putInt(2)
                putShort(1)
                putShort(24)
                putInt(0)
                putInt(16)
                putInt(2835)
                putInt(2835)
                putInt(0)
                putInt(0)
                // Bottom-up BGR rows, padded to four-byte boundaries.
                put(byteArrayOf(0, 0, -1, -1, -1, -1, 0, 0))
                put(byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0))
            }.array()

    private fun createPlayerOrFail(): Long {
        Assume.assumeTrue(CurrentPlatform.os == CurrentPlatform.OS.LINUX)
        return LinuxNativeBridge.nCreatePlayer().also { player ->
            assertNotEquals(0L, player, "Native Linux player creation failed")
        }
    }

    @Test
    fun `copy rejects heap byte buffer`() {
        val player = createPlayerOrFail()

        try {
            assertFailsWith<IllegalArgumentException> {
                LinuxNativeBridge.nCopyLatestFrame(
                    handle = player,
                    destination = ByteBuffer.allocate(16),
                    expectedWidth = 2,
                    expectedHeight = 2,
                    destinationStride = 8,
                    outInfo = IntArray(3),
                )
            }
        } finally {
            LinuxNativeBridge.nDisposePlayer(player)
        }
    }

    @Test
    fun `copy rejects read only direct byte buffer`() {
        val player = createPlayerOrFail()

        try {
            assertFailsWith<IllegalArgumentException> {
                LinuxNativeBridge.nCopyLatestFrame(
                    handle = player,
                    destination = ByteBuffer.allocateDirect(16).asReadOnlyBuffer(),
                    expectedWidth = 2,
                    expectedHeight = 2,
                    destinationStride = 8,
                    outInfo = IntArray(3),
                )
            }
        } finally {
            LinuxNativeBridge.nDisposePlayer(player)
        }
    }

    @Test
    fun `copy reports not ready through direct byte buffer`() {
        val player = createPlayerOrFail()
        val info = intArrayOf(7, 7, 7)

        try {
            val status =
                LinuxNativeBridge.nCopyLatestFrame(
                    handle = player,
                    destination = ByteBuffer.allocateDirect(16),
                    expectedWidth = 2,
                    expectedHeight = 2,
                    destinationStride = 8,
                    outInfo = info,
                )

            assertEquals(LinuxNativeBridge.FRAME_COPY_NOT_READY, status)
            assertContentEquals(intArrayOf(0, 0, 0), info)
        } finally {
            LinuxNativeBridge.nDisposePlayer(player)
        }
    }

    @Test
    fun `copy succeeds through real JNI and preserves destination padding`() {
        val player = createPlayerOrFail()
        var fixture: java.nio.file.Path? = null

        try {
            fixture = Files.createTempFile("compose-media-player-frame-", ".bmp")
            Files.write(fixture, twoByTwoBmp())
            LinuxNativeBridge.nOpenUri(player, fixture.toUri().toASCIIString())
            LinuxNativeBridge.nPlay(player)

            val destinationStride = 12
            val destination = ByteBuffer.allocateDirect(destinationStride * 2)
            repeat(destination.capacity()) { destination.put(it, 0x5a.toByte()) }
            val info = IntArray(3)
            var status = LinuxNativeBridge.FRAME_COPY_NOT_READY
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                if (LinuxNativeBridge.nGetFrameWidth(player) == 2 &&
                    LinuxNativeBridge.nGetFrameHeight(player) == 2
                ) {
                    status =
                        LinuxNativeBridge.nCopyLatestFrame(
                            handle = player,
                            destination = destination,
                            expectedWidth = 2,
                            expectedHeight = 2,
                            destinationStride = destinationStride,
                            outInfo = info,
                        )
                    if (status == LinuxNativeBridge.FRAME_COPY_OK) break
                }
                Thread.sleep(10)
            }

            assertEquals(LinuxNativeBridge.FRAME_COPY_OK, status)
            assertContentEquals(intArrayOf(2, 2, 8), info)
            val copied = ByteArray(destination.capacity())
            destination.position(0)
            destination.get(copied)
            assertTrue(copied.sliceArray(0 until 8).any { it != 0.toByte() })
            assertTrue(copied.sliceArray(12 until 20).any { it != 0.toByte() })
            assertTrue(copied.sliceArray(8 until 12).all { it == 0x5a.toByte() })
            assertTrue(copied.sliceArray(20 until 24).all { it == 0x5a.toByte() })
        } finally {
            try {
                LinuxNativeBridge.nDisposePlayer(player)
            } finally {
                fixture?.let { Files.deleteIfExists(it) }
            }
        }
    }
}
