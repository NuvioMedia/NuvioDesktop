package io.github.kdroidfilter.composemediaplayer.linux

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxFrameResourceGateTest {
    @Test
    fun `close waits until active frame resource use completes`() {
        val gate = LinuxFrameResourceGate()
        val copyEntered = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val closed = AtomicBoolean(false)

        val copier =
            thread(name = "frame-copy") {
                gate.withResources {
                    copyEntered.countDown()
                    assertTrue(releaseCopy.await(5, TimeUnit.SECONDS))
                }
            }
        assertTrue(copyEntered.await(5, TimeUnit.SECONDS))

        lateinit var disposer: Thread
        disposer =
            thread(start = false, name = "frame-dispose") {
                gate.withResources { closed.set(true) }
            }
        disposer.start()
        assertTrue(gate.waitUntilQueued(disposer, 5_000))
        assertFalse(closed.get())

        releaseCopy.countDown()
        copier.join(5_000)
        disposer.join(5_000)
        assertFalse(copier.isAlive)
        assertFalse(disposer.isAlive)
        assertTrue(closed.get())
    }
}
