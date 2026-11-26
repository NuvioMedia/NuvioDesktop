package com.nuvio.app.features.player

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxKeepAwakeControllerTest {
    @Before
    fun requireLinux() = assumeTrue(System.getProperty("os.name").contains("linux", ignoreCase = true))

    @Test
    fun holdsAcrossRecompositionsAndReleasesOnStopAndDispose() {
        val acquired = CountDownLatch(1)
        val released = CountDownLatch(1)
        val active = AtomicInteger()
        val calls = AtomicInteger()
        val processes = Collections.synchronizedList(mutableListOf<Process>())
        val controller = LinuxKeepAwakeController(
            acquireScreenSaver = {
                calls.incrementAndGet()
                active.incrementAndGet()
                acquired.countDown()
                AutoCloseable { active.decrementAndGet(); released.countDown() }
            },
            startSystemdInhibit = { startPipeChild().also { processes += it } },
        )
        try {
            controller.setEnabled(true)
            assertTrue(acquired.await(2, TimeUnit.SECONDS))
            repeat(100) { controller.setEnabled(true) }
            assertEquals(1, active.get())
            assertEquals(1, calls.get())
            controller.setEnabled(false)
            assertTrue(released.await(2, TimeUnit.SECONDS))
            assertEquals(0, active.get())
            controller.setEnabled(true)
        } finally {
            controller.close()
        }
        // Cleanup is queued after the second acquire, not cancelled by executor shutdown.
        awaitWorkerTermination()
        assertEquals(2, calls.get())
        assertEquals(0, active.get())
        assertEquals(2, processes.size)
        processes.forEach { assertTrue(it.waitFor(2, TimeUnit.SECONDS)); assertFalse(it.isAlive) }
    }

    @Test
    fun disposeDuringAcquireReleasesTheResultAndRejectsLaterEnable() {
        releaseDuringAcquire(dispose = true)
    }

    @Test
    fun stopDuringAcquireReleasesTheResult() {
        releaseDuringAcquire(dispose = false)
    }

    private fun releaseDuringAcquire(dispose: Boolean) {
        val started = CountDownLatch(1)
        val finishAcquire = CountDownLatch(1)
        val released = CountDownLatch(1)
        val calls = AtomicInteger()
        val controller = LinuxKeepAwakeController(
            acquireScreenSaver = {
                calls.incrementAndGet()
                started.countDown()
                check(finishAcquire.await(2, TimeUnit.SECONDS))
                AutoCloseable { released.countDown() }
            },
            startSystemdInhibit = ::startPipeChild,
        )
        try {
            controller.setEnabled(true)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            if (dispose) {
                controller.close()
                controller.close()
                controller.setEnabled(false)
                controller.setEnabled(true)
            } else {
                controller.setEnabled(false)
            }
            finishAcquire.countDown()
            assertTrue(released.await(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            finishAcquire.countDown()
            controller.close()
        }
        awaitWorkerTermination()
    }

    @Test
    fun unavailableScreenSaverStillReleasesFallback() {
        val started = CountDownLatch(1)
        lateinit var child: Process
        val calls = AtomicInteger()
        val controller = LinuxKeepAwakeController(
            acquireScreenSaver = { calls.incrementAndGet(); error("no session service") },
            startSystemdInhibit = { startPipeChild().also { child = it; started.countDown() } },
        )
        try {
            controller.setEnabled(true)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(100) { controller.setEnabled(true) }
            controller.setEnabled(false)
            assertTrue(child.waitFor(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun failedUninhibitDoesNotSkipFallbackCleanup() {
        val started = CountDownLatch(1)
        lateinit var child: Process
        val controller = LinuxKeepAwakeController(
            acquireScreenSaver = { AutoCloseable { error("UnInhibit failed") } },
            startSystemdInhibit = { startPipeChild().also { child = it; started.countDown() } },
        )
        try {
            controller.setEnabled(true)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            controller.close()
            assertTrue(child.waitFor(2, TimeUnit.SECONDS))
        } finally {
            controller.close()
        }
    }

    @Test
    fun fallbackFailureDoesNotDiscardScreenSaverHandle() {
        val acquired = CountDownLatch(1)
        val released = CountDownLatch(1)
        val controller = LinuxKeepAwakeController(
            acquireScreenSaver = { acquired.countDown(); AutoCloseable { released.countDown() } },
            startSystemdInhibit = { error("systemd-inhibit unavailable") },
        )
        try {
            controller.setEnabled(true)
            assertTrue(acquired.await(2, TimeUnit.SECONDS))
            controller.close()
            assertTrue(released.await(2, TimeUnit.SECONDS))
        } finally {
            controller.close()
        }
    }

    @Test
    fun disposeBeforePlaybackDoesNotAcquireAnything() {
        val calls = AtomicInteger()
        val controller = LinuxKeepAwakeController(
            acquireScreenSaver = { calls.incrementAndGet(); AutoCloseable {} },
            startSystemdInhibit = { error("must not start") },
        )
        controller.setEnabled(false)
        controller.close()
        controller.close()
        controller.setEnabled(true)
        assertEquals(0, calls.get())
    }

    private fun startPipeChild(): Process = ProcessBuilder("cat")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

    private fun awaitWorkerTermination() {
        // Only tests own these workers; assert disposal also drains and shuts down the executor.
        val workers = Thread.getAllStackTraces().keys.filter { it.name == "nuvio-linux-screensaver-inhibit" }
        workers.forEach { it.join(2500); assertFalse(it.isAlive) }
    }
}
