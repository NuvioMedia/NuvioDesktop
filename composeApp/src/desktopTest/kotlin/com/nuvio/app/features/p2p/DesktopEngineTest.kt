package com.nuvio.app.features.p2p

import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.engine.NuvioEngineRuntime
import com.nuvio.engine.NuvioTorrentProfile
import com.nuvio.engine.NuvioUploadMode
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopEngineTest {
    @Test
    fun appOwnedRoutesDisableAutomaticInactivityExpiry() {
        val uploading = buildNuvioEngineConfig(
            stateDirectory = File("state"),
            cacheDirectory = File("cache"),
            uploadEnabled = true,
            torrentProfile = P2pTorrentProfile.FAST,
            diskCacheCapacityBytes = P2pCacheSize.GB_5.bytes,
        )
        val downloadOnly = buildNuvioEngineConfig(
            stateDirectory = File("state"),
            cacheDirectory = File("cache"),
            uploadEnabled = false,
            torrentProfile = P2pTorrentProfile.SOFT,
            diskCacheCapacityBytes = P2pCacheSize.NONE.bytes,
        )

        assertEquals(0, uploading.streamInactivityTimeoutMilliseconds)
        assertEquals(NuvioUploadMode.Unlimited, uploading.uploadMode)
        assertEquals(NuvioTorrentProfile.Fast, uploading.torrentProfile)
        assertEquals(P2pCacheSize.GB_5.bytes, uploading.diskCacheCapacityBytes)
        assertEquals(NuvioUploadMode.Disabled, downloadOnly.uploadMode)
        assertEquals(NuvioTorrentProfile.Soft, downloadOnly.torrentProfile)
        assertEquals(0L, downloadOnly.diskCacheCapacityBytes)
    }

    @Test
    fun matchingUnsolicitedStopBecomesTerminalError() {
        assertEquals(
            P2pStreamingState.Error("stream expired after inactivity"),
            unexpectedStreamStopError(0L, "stream", "stream", "stream expired after inactivity", "unknown"),
        )
        assertEquals(
            P2pStreamingState.Error("unknown"),
            unexpectedStreamStopError(0L, "stream", "stream", "  ", "unknown"),
        )
    }

    @Test
    fun explicitAndStaleEventsAreIgnored() {
        assertNull(unexpectedStreamStopError(7L, "stream", "stream", "stopped", "unknown"))
        assertNull(unexpectedStreamStopError(0L, "old-stream", "stream", "stopped", "unknown"))
        assertNull(unexpectedTorrentError(9L, "torrent", "torrent", "failed", "unknown"))
        assertNull(unexpectedTorrentError(0L, "old-torrent", "torrent", "failed", "unknown"))
        assertNull(unexpectedTorrentError(0L, null, "torrent", "disk cache budget exceeded", "unknown"))
        assertEquals(
            P2pStreamingState.Error("file write failed"),
            unexpectedTorrentError(0L, "torrent", "torrent", "file write failed", "unknown"),
        )
    }

    @Test
    fun libraryNamesMatchPackagedFiles() {
        assertEquals("libnuvio_engine.dylib", DesktopEngineLibrary.libraryName(DesktopHostOs.MACOS))
        assertEquals("nuvio_engine.dll", DesktopEngineLibrary.libraryName(DesktopHostOs.WINDOWS))
        assertEquals("libnuvio_engine.so", DesktopEngineLibrary.libraryName(DesktopHostOs.LINUX))
    }

    @Test
    fun bundledLibraryLoadsAndCreatesEngine() {
        val runtime = NuvioEngineRuntime.load(DesktopEngineLibrary.resolve())
        assertEquals("0.1.2", runtime.version)

        val root = Files.createTempDirectory("nuvio-engine-desktop-").toFile()
        try {
            val engine = runtime.create(
                buildNuvioEngineConfig(
                    stateDirectory = File(root, "state").apply { mkdirs() },
                    cacheDirectory = File(root, "cache").apply { mkdirs() },
                    uploadEnabled = false,
                    torrentProfile = P2pTorrentProfile.BALANCED,
                    diskCacheCapacityBytes = P2pCacheSize.NONE.bytes,
                ),
            )
            engine.close()
            assertTrue(root.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}
