package com.nuvio.app.features.player

import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Run via scripts/test-linux-screensaver-inhibit.py in a disposable dbus-run-session. */
class LinuxScreenSaverInhibitorDbusTest {
    @Before
    fun requireMockBus() = assumeTrue(System.getenv("NUVIO_INHIBITOR_DBUS_TEST") == "1")

    @Test
    fun unsignedCookieStaysActiveAndIsReleasedByTheSameSender() {
        reset("4294967295")
        val inhibitor = LinuxScreenSaverInhibitor.acquire()
        try {
            repeat(5) {
                assertEquals(1, state("active"))
                assertEquals(0, state("disconnects"))
                Thread.sleep(100)
            }
        } finally {
            inhibitor.close()
        }
        inhibitor.close()
        awaitState("active", 0)
        awaitState("disconnects", 1)
        assertEquals(1, state("uninhibits"))
        assertEquals(0, state("wrongSender"))
        assertEquals(0, state("autoRemoved"))
    }

    @Test
    fun zeroIsAlsoAValidCookie() {
        reset("0")
        LinuxScreenSaverInhibitor.acquire().use {
            assertEquals(1, state("active"))
        }
        awaitState("active", 0)
        assertEquals(1, state("uninhibits"))
        assertEquals(0, state("wrongSender"))
    }

    @Test
    fun controllerKeepsRealConnectionAcrossUpdatesAndReleasesOnStopAndDispose() {
        reset()
        val controller = LinuxKeepAwakeController(startSystemdInhibit = { error("no logind in mock bus") })
        try {
            controller.setEnabled(true)
            awaitState("active", 1)
            repeat(100) { controller.setEnabled(true) }
            Thread.sleep(200)
            assertEquals(1, state("active"))
            assertEquals(1, state("acquisitions"))
            controller.setEnabled(false)
            awaitState("active", 0)
            controller.setEnabled(true)
            awaitState("active", 1)
        } finally {
            controller.close()
        }
        awaitState("active", 0)
        assertEquals(2, state("uninhibits"))
        assertEquals(0, state("wrongSender"))
    }

    @Test
    fun disposeWhileInhibitReplyIsPendingStillReleasesTheAcquiredCookie() {
        reset(mode = "delay")
        val controller = LinuxKeepAwakeController(startSystemdInhibit = { error("no logind in mock bus") })
        try {
            controller.setEnabled(true)
            awaitState("active", 1)
            controller.close()
            awaitState("active", 0)
            assertEquals(1, state("uninhibits"))
            assertEquals(0, state("wrongSender"))
        } finally {
            controller.close()
        }
    }

    @Test
    fun failedUninhibitDisconnectsAndReleasesTheCookieAnyway() {
        reset(mode = "fail-release")
        val inhibitor = LinuxScreenSaverInhibitor.acquire()
        assertFailsWith<IllegalStateException> { inhibitor.close() }
        inhibitor.close()
        awaitState("active", 0)
        assertEquals(1, state("autoRemoved"))
    }

    @Test
    fun timedOutUninhibitDisconnectsAndReleasesTheCookieAnyway() {
        reset(mode = "timeout-release")
        val inhibitor = LinuxScreenSaverInhibitor.acquire()
        val started = System.nanoTime()
        assertFailsWith<IllegalStateException> { inhibitor.close() }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 6000)
        inhibitor.close()
        awaitState("active", 0)
        assertEquals(1, state("autoRemoved"))
    }

    @Test
    fun malformedCookieReplyDoesNotLeakTheConnectionOrInhibition() {
        reset(mode = "bad-cookie")
        assertFailsWith<IllegalStateException> { LinuxScreenSaverInhibitor.acquire() }
        awaitState("active", 0)
        assertEquals(1, state("autoRemoved"))
    }

    @Test
    fun timedOutAcquireClosesItsConnectionAndReleasesAnyServerSideCookie() {
        reset(mode = "timeout")
        val started = System.nanoTime()
        assertFailsWith<IllegalStateException> { LinuxScreenSaverInhibitor.acquire() }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 6000)
        awaitState("active", 0)
        assertEquals(1, state("autoRemoved"))
    }

    private fun reset(cookie: String = "42", mode: String = "normal") {
        call("Reset", "uint32:$cookie", "string:$mode")
    }

    private fun state(key: String): Int =
        checkNotNull(Regex("$key=(\\d+)").find(call("GetState"))).groupValues[1].toInt()

    private fun awaitState(key: String, value: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (state(key) != value && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(value, state(key))
    }

    private fun call(method: String, vararg args: String): String {
        val process = ProcessBuilder(
            "dbus-send", "--session", "--type=method_call", "--print-reply=literal", "--reply-timeout=2000",
            "--dest=org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver",
            "com.nuvio.Test.$method", *args,
        ).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(3, TimeUnit.SECONDS), "mock service did not reply")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.exitValue(), output)
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
