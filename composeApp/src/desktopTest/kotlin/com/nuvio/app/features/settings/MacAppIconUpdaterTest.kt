package com.nuvio.app.features.settings

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacAppIconUpdaterTest {
    @Test
    fun `finds app bundle from packaged resources directory`() {
        val resourcesDirectory = Path.of(
            "/Applications/Nuvio.app/Contents/app/resources",
        )

        assertEquals(
            Path.of("/Applications/Nuvio.app"),
            findMacApplicationBundle(listOf(resourcesDirectory)),
        )
    }

    @Test
    fun `finds app bundle from packaged application home`() {
        val applicationHome = Path.of(
            "/Applications/Nuvio.app/Contents/app",
        )

        assertEquals(
            Path.of("/Applications/Nuvio.app"),
            findMacApplicationBundle(listOf(applicationHome)),
        )
    }

    @Test
    fun `ignores paths outside an app bundle`() {
        assertNull(
            findMacApplicationBundle(
                listOf(Path.of("/Users/example/Nuvio/resources")),
            ),
        )
    }

    @Test
    fun `targets icon declared by packaged app`() {
        assertEquals(
            Path.of("/Applications/Nuvio.app/Contents/Resources/Nuvio.icns"),
            macosBundleIconPath(Path.of("/Applications/Nuvio.app")),
        )
    }

    @Test
    fun `restart waits for current process before reopening pinned app`() {
        assertEquals(
            listOf(
                "/bin/sh",
                "-c",
                "while kill -0 \"\$1\" 2>/dev/null; do sleep 0.1; done; exec /usr/bin/open \"\$2\"",
                "nuvio-macos-restart",
                "1234",
                "/Applications/Nuvio.app",
            ),
            macosRestartCommand(
                appBundle = Path.of("/Applications/Nuvio.app"),
                currentPid = 1234,
            ),
        )
    }
}
