package io.github.kdroidfilter.composemediaplayer.linux

import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Keeps a generation-bound native player alive while JNI calls hold leases.
 * Bookkeeping locks are released before JNI work and before destruction waits.
 */
internal class LinuxNativePlayerGate(
    private val retirementWaitingForLeasesForTest: (() -> Unit)? = null,
) {
    internal data class Record(
        val handle: Long,
        val sourceGeneration: Long,
        var leases: Int = 0,
    )

    internal class Retirement internal constructor(
        private val lock: ReentrantLock,
        private val leasesReleased: Condition,
        private val record: Record,
        private val waitingForLeasesForTest: (() -> Unit)?,
    ) {
        fun awaitHandle(): Long {
            var reportedWaiting = false
            while (true) {
                var reportWaiting = false
                lock.withLock {
                    if (record.leases == 0) return record.handle
                    if (!reportedWaiting) {
                        reportedWaiting = true
                        reportWaiting = true
                    } else {
                        leasesReleased.await()
                    }
                }
                if (reportWaiting) {
                    try {
                        waitingForLeasesForTest?.invoke()
                    } catch (_: Throwable) {
                        // Diagnostic observers must not abort mandatory retirement drainage.
                    }
                }
            }
        }
    }

    internal class Lease internal constructor(
        private val gate: LinuxNativePlayerGate,
        private val record: Record,
    ) {
        fun <T> use(block: (Long) -> T): T =
            try {
                block(record.handle)
            } finally {
                gate.release(record)
            }
    }

    private val lock = ReentrantLock(true)
    private val leasesReleased = lock.newCondition()
    private var record: Record? = null
    private var closed = false

    fun install(
        handle: Long,
        sourceGeneration: Long,
    ): Boolean {
        if (handle == 0L || sourceGeneration == 0L) return false
        return lock.withLock {
            if (closed || record != null) {
                false
            } else {
                record = Record(handle, sourceGeneration)
                true
            }
        }
    }

    fun acquire(expectedSourceGeneration: Long): Lease? =
        lock.withLock {
            val current = record
            if (closed || current == null || current.sourceGeneration != expectedSourceGeneration) {
                null
            } else if (current.leases == Int.MAX_VALUE) {
                null
            } else {
                current.leases += 1
                Lease(this, current)
            }
        }

    fun <T> withPlayer(
        expectedSourceGeneration: Long,
        block: (Long) -> T,
    ): T? = acquire(expectedSourceGeneration)?.use(block)

    fun retire(): Retirement? = lock.withLock { retireLocked() }

    fun close(): Retirement? =
        lock.withLock {
            closed = true
            retireLocked()
        }

    private fun retireLocked(): Retirement? {
        val retired = record ?: return null
        record = null
        return Retirement(lock, leasesReleased, retired, retirementWaitingForLeasesForTest)
    }

    private fun release(leased: Record) {
        lock.withLock {
            leased.leases -= 1
            check(leased.leases >= 0) { "Native player lease count underflow" }
            if (leased.leases == 0) leasesReleased.signalAll()
        }
    }
}
