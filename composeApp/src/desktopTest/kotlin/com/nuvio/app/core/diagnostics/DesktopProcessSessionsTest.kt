package com.nuvio.app.core.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopProcessSessionsTest {
    private val root: Path = createTempDirectory("nuvio-sessions-test")
    private val sessionsDir = root.resolve("sessions")
    private val now = 1_800_000_000_000L

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun session(pid: Long, startedAt: Long = now - 60_000, workingDir: String = "") =
        DesktopProcessSession(
            pid = pid,
            startedAtMillis = startedAt,
            workingDir = workingDir,
            versionName = "0.1.38-alpha",
            versionCode = 138,
        )

    @Test
    fun returnsAnEndedSessionOnce() {
        val store = DesktopProcessSessionStore(sessionsDir)
        val ended = session(pid = 41)
        store.record(ended)

        assertEquals(listOf(ended), store.takeEndedSessions(now) { false })
        assertEquals(emptyList(), store.takeEndedSessions(now) { false })
        assertEquals(emptyList(), sessionsDir.listDirectoryEntries())
    }

    @Test
    fun leavesRunningSessionsAlone() {
        val store = DesktopProcessSessionStore(sessionsDir)
        val running = session(pid = 42)
        val ended = session(pid = 43)
        store.record(running)
        store.record(ended)

        assertEquals(listOf(ended), store.takeEndedSessions(now) { it.pid == 42L })
        assertEquals(listOf(running), store.takeEndedSessions(now) { false })
    }

    @Test
    fun keepsSessionsOfTheSameProcessIdApart() {
        val store = DesktopProcessSessionStore(sessionsDir)
        val earlier = session(pid = 44, startedAt = now - 120_000)
        val current = session(pid = 44, startedAt = now - 1_000)
        store.record(earlier)
        store.record(current)

        assertEquals(listOf(earlier), store.takeEndedSessions(now) { it == current })
    }

    @Test
    fun forgetsOldSessionsWithoutReportingThem() {
        val store = DesktopProcessSessionStore(sessionsDir, maxAgeMillis = 10_000)
        store.record(session(pid = 45, startedAt = now - 20_000))

        assertEquals(emptyList(), store.takeEndedSessions(now) { true })
        assertEquals(emptyList(), sessionsDir.listDirectoryEntries())
    }

    @Test
    fun deletesUnreadableSessionFiles() {
        val store = DesktopProcessSessionStore(sessionsDir)
        Files.createDirectories(sessionsDir)
        sessionsDir.resolve("46-1.session").writeText("pid=not-a-number")

        assertEquals(emptyList(), store.takeEndedSessions(now) { false })
        assertEquals(emptyList(), sessionsDir.listDirectoryEntries())
    }

    @Test
    fun findsTheLogInTheWorkingDirectoryFirst() {
        val workingDir = Files.createDirectories(root.resolve("work"))
        val tempDir = Files.createDirectories(root.resolve("tmp"))
        val crashed = session(pid = 47, workingDir = workingDir.toString())
        val inWorkingDir = writeLog(workingDir, pid = 47, modifiedAt = now)
        writeLog(tempDir, pid = 47, modifiedAt = now)

        assertEquals(inWorkingDir, findJvmFatalErrorLog(crashed, listOf(tempDir.toString())))
    }

    @Test
    fun fallsBackToTheTemporaryDirectory() {
        val workingDir = Files.createDirectories(root.resolve("work"))
        val tempDir = Files.createDirectories(root.resolve("tmp"))
        val crashed = session(pid = 48, workingDir = workingDir.toString())
        val inTempDir = writeLog(tempDir, pid = 48, modifiedAt = now)

        assertEquals(inTempDir, findJvmFatalErrorLog(crashed, listOf(tempDir.toString())))
    }

    @Test
    fun ignoresLogsWrittenBeforeTheSessionStarted() {
        val tempDir = Files.createDirectories(root.resolve("tmp"))
        val crashed = session(pid = 49, startedAt = now)
        writeLog(tempDir, pid = 49, modifiedAt = now - 3_600_000)

        assertNull(findJvmFatalErrorLog(crashed, listOf(tempDir.toString())))
    }

    @Test
    fun ignoresEmptyAndOversizedLogs() {
        val tempDir = Files.createDirectories(root.resolve("tmp"))
        val empty = tempDir.resolve("hs_err_pid50.log").also { Files.createFile(it) }
        Files.setLastModifiedTime(empty, FileTime.fromMillis(now))
        writeLog(tempDir, pid = 51, modifiedAt = now)

        assertNull(findJvmFatalErrorLog(session(pid = 50), listOf(tempDir.toString())))
        assertNull(findJvmFatalErrorLog(session(pid = 51), listOf(tempDir.toString()), maxBytes = 4))
        assertTrue(findJvmFatalErrorLog(session(pid = 51), listOf(tempDir.toString())) != null)
    }

    private fun writeLog(dir: Path, pid: Long, modifiedAt: Long): Path =
        dir.resolve("hs_err_pid$pid.log").also { file ->
            file.writeText(JvmFatalErrorLogSamples.linuxSegfault)
            Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedAt))
        }
}
