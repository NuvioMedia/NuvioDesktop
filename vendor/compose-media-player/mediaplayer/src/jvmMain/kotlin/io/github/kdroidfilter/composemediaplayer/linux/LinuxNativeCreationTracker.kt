package io.github.kdroidfilter.composemediaplayer.linux

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Tracks native-player creation until ownership is either installed or rejected
 * and destroyed. The lock protects only counters; it is never held while
 * creating, installing, destroying, waiting, or invoking observers.
 */
internal class LinuxNativeCreationTracker(
    private val waitingForCreationsForTest: ((Int) -> Unit)? = null,
) {
    private val lock = ReentrantLock(true)
    private val quiescent = lock.newCondition()
    private val waiterChanged = lock.newCondition()
    private var closed = false
    private var active = 0
    private var quiescenceWaiters = 0

    suspend fun <T> track(block: suspend () -> T): T? {
        lock.withLock {
            if (closed) return null
            check(active != Int.MAX_VALUE) { "Native creation count exhausted" }
            active += 1
        }
        return try {
            block()
        } finally {
            lock.withLock {
                active -= 1
                check(active >= 0) { "Native creation count underflow" }
                if (active == 0) quiescent.signalAll()
            }
        }
    }

    fun close() {
        lock.withLock { closed = true }
    }

    fun awaitQuiescence() {
        while (true) {
            val observed = lock.withLock { active }
            if (observed == 0) return
            try {
                waitingForCreationsForTest?.invoke(observed)
            } catch (_: Throwable) {
                // Diagnostic observers must not abort mandatory creation drainage.
            }
            lock.withLock {
                while (active != 0) {
                    quiescenceWaiters += 1
                    waiterChanged.signalAll()
                    try {
                        quiescent.await()
                    } finally {
                        quiescenceWaiters -= 1
                    }
                }
            }
        }
    }

    internal fun awaitQuiescenceWaitEnteredForTest() {
        lock.withLock {
            while (quiescenceWaiters == 0) waiterChanged.await()
        }
    }
}
