package com.nuvio.app.core.diagnostics

import io.sentry.SentryLevel
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NativeCrashEventTest {
    private val session = DesktopProcessSession(
        pid = 54704,
        startedAtMillis = 1_800_000_000_000L,
        workingDir = "",
        versionName = "0.1.37-alpha",
        versionCode = 137,
    )
    private val metadata = desktopSentryMetadata(
        osName = "Windows 11",
        osArchitecture = "amd64",
        versionName = session.versionName,
        versionCode = session.versionCode,
    )

    @Test
    fun describesTheCrashOfTheVersionThatWasRunning() {
        val log = assertNotNull(JvmFatalErrorLog.parse(JvmFatalErrorLogSamples.windowsAccessViolation))
        val crashedAt = Date(1_800_000_812_000L)

        val event = nativeCrashEvent(log, session, crashedAt, metadata)

        assertEquals(SentryLevel.FATAL, event.level)
        assertEquals(crashedAt, event.timestamp)
        assertEquals("com.nuvio.media.desktop@0.1.37-alpha+137", event.release)
        assertEquals("137-windows-x86_64", event.dist)
        assertEquals("0.1.37-alpha", event.getTag("app.version_name"))
        assertEquals("native", event.getTag("crash.kind"))
        assertEquals("libmpv-2.dll", event.getTag("crash.library"))
        assertEquals("AWT-EventQueue-0", event.getTag("crash.thread"))
        assertEquals(
            listOf("jvm-fatal-error", "EXCEPTION_ACCESS_VIOLATION", "C  [libmpv-2.dll+0x1f2a3]"),
            event.fingerprints,
        )

        val exception = assertNotNull(event.exceptions).single()
        assertEquals("EXCEPTION_ACCESS_VIOLATION", exception.type)
        assertEquals("C  [libmpv-2.dll+0x1f2a3]", exception.value)
        assertEquals("jvm_fatal_error", exception.mechanism?.type)
        assertEquals(false, exception.mechanism?.isHandled)

        val frames = assertNotNull(exception.stacktrace?.frames)
        assertEquals(7, frames.size)
        // Outermost first, the crash site last.
        assertEquals("libmpv-2.dll", frames.last().`package`)
        assertEquals("~StubRoutines::call_stub", frames.first().function)
    }

    @Test
    fun readsNativeFramesWithAndWithoutSymbols() {
        val withSymbol = nativeCrashStackFrame("C  [libplayer_bridge.so+0x8c40]  nuvio_bridge_destroy+0x30")
        assertEquals("libplayer_bridge.so", withSymbol.`package`)
        assertEquals("nuvio_bridge_destroy", withSymbol.function)
        assertEquals(true, withSymbol.isNative)
        assertEquals(true, withSymbol.isInApp)

        val withoutSymbol = nativeCrashStackFrame("V  [jvm.dll+0x88d4c7]  (no source info available)")
        assertEquals("jvm.dll", withoutSymbol.`package`)
        assertEquals("jvm.dll+0x88d4c7", withoutSymbol.function)
        assertEquals(false, withoutSymbol.isInApp)
    }

    @Test
    fun readsJavaFrames() {
        val interpreted = nativeCrashStackFrame(
            "j  com.nuvio.app.features.player.desktop.NativePlayerBridge.nativeSeek(JD)V+0",
        )
        assertEquals("com.nuvio.app.features.player.desktop.NativePlayerBridge", interpreted.module)
        assertEquals("nativeSeek", interpreted.function)
        assertEquals(true, interpreted.isInApp)
        assertNull(interpreted.isNative)

        val compiled = nativeCrashStackFrame(
            "J 1234 c2 java.awt.EventDispatchThread.pumpOneEventForFilters(I)V java.desktop@17.0.13 (106 bytes) @ 0x0000013281a5b2c4",
        )
        assertEquals("java.awt.EventDispatchThread", compiled.module)
        assertEquals("pumpOneEventForFilters", compiled.function)
        assertEquals(false, compiled.isInApp)
    }

    @Test
    fun keepsFramesWithoutALibrary() {
        val frame = nativeCrashStackFrame("C  0x00000132d733ce8b  (no source info available)")
        assertEquals("0x00000132d733ce8b", frame.function)
        assertEquals(false, frame.isInApp)
    }
}
