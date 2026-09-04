package io.github.kdroidfilter.composemediaplayer.linux

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes native frame writes with closing their Skia-backed destinations. */
internal class LinuxFrameResourceGate {
    private val lock = ReentrantLock()

    fun <T> withResources(block: () -> T): T = lock.withLock(block)

    /** Test seam that observes an actual lock queue rather than inferring from elapsed time. */
    internal fun waitUntilQueued(
        thread: Thread,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (lock.hasQueuedThread(thread)) return true
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        return lock.hasQueuedThread(thread)
    }
}
