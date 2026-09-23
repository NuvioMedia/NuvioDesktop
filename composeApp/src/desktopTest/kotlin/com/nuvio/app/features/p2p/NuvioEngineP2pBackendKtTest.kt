package com.nuvio.app.features.p2p

import com.nuvio.app.features.player.DesktopBufferPreset
import com.nuvio.engine.NuvioTorrentProfile
import com.nuvio.engine.NuvioUploadMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NuvioEngineP2pBackendKtTest {

    @Test
    fun `nuvioEngineWindowBytes maps presets correctly`() {
        assertEquals(64L * 1024L * 1024L, nuvioEngineWindowBytes(DesktopBufferPreset.Metered))
        assertEquals(128L * 1024L * 1024L, nuvioEngineWindowBytes(DesktopBufferPreset.LowData))
        assertEquals(256L * 1024L * 1024L, nuvioEngineWindowBytes(DesktopBufferPreset.Balanced))
        assertEquals(512L * 1024L * 1024L, nuvioEngineWindowBytes(DesktopBufferPreset.Resilient))
    }

    @Test
    fun `buildNuvioEngineConfig maps settings and directories accurately`() {
        val stateDir = File("build/tmp/test-p2p-state")
        val cacheDir = File("build/tmp/test-p2p-cache")

        val config = buildNuvioEngineConfig(
            stateDirectory = stateDir,
            cacheDirectory = cacheDir,
            uploadEnabled = true,
            torrentProfile = P2pTorrentProfile.BALANCED,
            diskCacheCapacityBytes = 1024L * 1024L * 1024L,
            windowBytes = 256L * 1024L * 1024L,
        )

        assertEquals(stateDir, config.dataDirectory)
        assertEquals(cacheDir, config.cacheDirectory)
        assertEquals(256L * 1024L * 1024L, config.memoryCacheCapacityBytes)
        assertEquals(1024L * 1024L * 1024L, config.diskCacheCapacityBytes)
        assertEquals(NuvioTorrentProfile.Balanced, config.torrentProfile)
        assertEquals(NuvioUploadMode.Unlimited, config.uploadMode)
        assertEquals(0, config.streamInactivityTimeoutMilliseconds)
    }

    @Test
    fun `metadataWaitVerdict detects progress and timeouts correctly`() {
        // Normal wait within limits
        val waiting = metadataWaitVerdict(
            waitedMs = 5_000L,
            stalledForMs = 2_000L,
            knownPeers = 3L,
        )
        assertNull(waiting)

        // General timeout at or after 60s
        val generalTimeout = metadataWaitVerdict(
            waitedMs = 60_000L,
            stalledForMs = 0L,
            knownPeers = 5L,
        )
        assertEquals("Torrent metadata did not arrive within 60s", generalTimeout)

        // Stalled with 0 known peers
        val zeroPeersStall = metadataWaitVerdict(
            waitedMs = 25_000L,
            stalledForMs = 16_000L,
            knownPeers = 0L,
        )
        assertEquals("No peers found for this torrent", zeroPeersStall)

        // Stalled with unreachable peers
        val unreachablePeersStall = metadataWaitVerdict(
            waitedMs = 25_000L,
            stalledForMs = 16_000L,
            knownPeers = 4L,
        )
        assertEquals("No reachable peers for this torrent (4 found, none answered)", unreachablePeersStall)
    }

    @Test
    fun `unexpectedStreamStopError creates formatted error message on active stream`() {
        val error = unexpectedStreamStopError(
            requestId = 0L,
            eventStreamId = "stream-1",
            currentStreamId = "stream-1",
            message = "Socket reset",
            fallbackMessage = "Playback stopped unexpectedly",
        )
        assertEquals("Socket reset", error?.message)

        // If requestId != 0L, it was expected
        val expected = unexpectedStreamStopError(
            requestId = 10L,
            eventStreamId = "stream-1",
            currentStreamId = "stream-1",
            message = "Socket reset",
            fallbackMessage = "Playback stopped unexpectedly",
        )
        assertNull(expected)
    }
}
