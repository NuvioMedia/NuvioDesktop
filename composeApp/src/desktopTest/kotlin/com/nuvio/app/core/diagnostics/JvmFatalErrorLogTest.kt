package com.nuvio.app.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmFatalErrorLogTest {
    @Test
    fun readsTheCrashFromAWindowsLog() {
        val log = assertNotNull(
            JvmFatalErrorLog.parse(JvmFatalErrorLogSamples.windowsAccessViolation, homeDir = """C:\Users\tester"""),
        )

        assertEquals("EXCEPTION_ACCESS_VIOLATION", log.errorType)
        assertEquals("C  [libmpv-2.dll+0x1f2a3]", log.problematicFrame)
        assertEquals("C  [libmpv-2.dll+0x1f2a3]", log.crashLocation)
        assertEquals("libmpv-2.dll", log.crashLibrary)
        assertEquals("AWT-EventQueue-0", log.crashingThread)
        assertEquals("C  [libmpv-2.dll+0x1f2a3]", log.frames.first())
        assertEquals(7, log.frames.size)
    }

    @Test
    fun summaryKeepsWhatHelpsFixTheCrash() {
        val summary = assertNotNull(
            JvmFatalErrorLog.parse(JvmFatalErrorLogSamples.windowsAccessViolation, homeDir = """C:\Users\tester"""),
        ).summary

        listOf(
            "EXCEPTION_ACCESS_VIOLATION (0xc0000005)",
            "Host: AMD Ryzen 7 5800X",
            "Elapsed time: 812.386227 seconds (0d 0h 13m 32s)",
            "JavaThread \"AWT-EventQueue-0\"",
            "Native frames:",
            "C  [player_bridge.dll+0x4b10]",
            "Java frames:",
            "NativePlayerBridge.nativeSeek",
            "siginfo: EXCEPTION_ACCESS_VIOLATION",
            " Windows 11 , 64 bit Build 26100",
            "CPU: total 16",
            "Memory: 4k page",
            "vm_info: OpenJDK",
            "Loaded native libraries:\nntdll.dll\nlibmpv-2.dll\nplayer_bridge.dll\nnvoglv64.dll\n",
        ).forEach { expected ->
            assertTrue(summary.contains(expected), "summary should contain <$expected>:\n$summary")
        }
    }

    @Test
    fun summaryLeavesOutPrivateSections() {
        val summary = assertNotNull(
            JvmFatalErrorLog.parse(JvmFatalErrorLogSamples.windowsAccessViolation, homeDir = """C:\Users\tester"""),
        ).summary

        listOf(
            "Command Line",
            "secret-link",
            "Environment Variables",
            "USERNAME",
            "do-not-send",
            "tester",
            "Registers",
            "RAX=",
            "Sat Nov 21",
            "Program Files",
            "Processor Information",
            "TotalPageFile",
        ).forEach { unexpected ->
            assertFalse(summary.contains(unexpected), "summary should not contain <$unexpected>:\n$summary")
        }
    }

    @Test
    fun readsALinuxLogWithSymbols() {
        val log = assertNotNull(
            JvmFatalErrorLog.parse(JvmFatalErrorLogSamples.linuxSegfault, homeDir = "/home/tester"),
        )

        assertEquals("SIGSEGV", log.errorType)
        assertEquals("C  [libX11.so.6+0x4a1b2]  _XIOError+0x52", log.problematicFrame)
        assertEquals("libX11.so.6", log.crashLibrary)
        assertEquals("nuvio-player-teardown", log.crashingThread)
        // The Java frame printed in both sections is kept once.
        assertEquals(4, log.frames.size)

        val summary = log.summary
        assertTrue(summary.contains("Default location: ~/core.4242"), summary)
        assertTrue(summary.contains("OS:\nPRETTY_NAME=\"Ubuntu 24.04.1 LTS\""), summary)
        assertTrue(summary.contains("libc: glibc 2.39"), summary)
        assertTrue(
            summary.contains(
                "Loaded native libraries:\nlibX11.so.6.4.0\nlibplayer_bridge.so\nlibnvidia-glcore.so.550.120\n",
            ),
            summary,
        )
        listOf("tester", "/proc/meminfo", "MemTotal", "[heap]", "composeApp.jar", ".mount_Nuvio").forEach {
            assertFalse(summary.contains(it), "summary should not contain <$it>:\n$summary")
        }
    }

    @Test
    fun usesTheSourceLocationWhenThereIsNoProblematicFrame() {
        val log = assertNotNull(JvmFatalErrorLog.parse(JvmFatalErrorLogSamples.internalError))

        assertEquals("Internal Error", log.errorType)
        assertNull(log.problematicFrame)
        assertNull(log.crashLibrary)
        assertEquals("g1ConcurrentMark.cpp:1234", log.crashLocation)
        assertEquals("G1 Conc#0", log.crashingThread)
    }

    @Test
    fun groupsLibrariesUnpackedUnderARandomName() {
        val text = JvmFatalErrorLogSamples.windowsAccessViolation.replace(
            "libmpv-2.dll+0x1f2a3",
            "quickjs_quickjs15849965290451525793.tmp+0x18da6",
        )
        val log = assertNotNull(JvmFatalErrorLog.parse(text))

        assertEquals("C  [quickjs_quickjs*.tmp+0x18da6]", log.crashLocation)
        assertEquals("quickjs_quickjs*.tmp", log.crashLibrary)
        assertEquals("jna*.dll", JvmFatalErrorLog.stableNativeLibraryName("jna1416430014348064172.dll"))
        assertEquals("libmpv-2.dll", JvmFatalErrorLog.stableNativeLibraryName("libmpv-2.dll"))
        assertEquals(
            "quickjs_quickjs*.tmp",
            nativeCrashStackFrame("C  [quickjs_quickjs15849965290451525793.tmp+0x18da6]").`package`,
        )
    }

    @Test
    fun rejectsTextThatIsNotAFatalErrorLog() {
        assertNull(JvmFatalErrorLog.parse(""))
        assertNull(JvmFatalErrorLog.parse("Exception in thread \"main\" java.lang.IllegalStateException"))
        assertNull(JvmFatalErrorLog.parse("#\n# A comment without an error line\n#\n"))
    }
}
