package io.github.kdroidfilter.composemediaplayer.linux

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Coordinates source generations, generation-bound native leases, and deferred
 * retirement. No lifecycle lock is held while waiting for leases, running JNI,
 * cancelling jobs, destroying players, or invoking callbacks.
 */
internal class LinuxPlayerLifecycle(
    private val generations: LinuxPlayerGeneration = LinuxPlayerGeneration(),
    private val gate: LinuxNativePlayerGate = LinuxNativePlayerGate(),
    private val afterGenerationAdvanced: (() -> Unit)? = null,
    private val transitionWaitingForCallbacksForTest: (() -> Unit)? = null,
    private val rejectedEnqueuedForTest: ((Long) -> Unit)? = null,
    private val retirementInterruptedForTest: (() -> Unit)? = null,
) {
    sealed interface InstallResult {
        data object Installed : InstallResult

        data object StaleOrClosed : InstallResult

        data class WaitForDrain(
            val completion: CompletableFuture<Int>,
        ) : InstallResult

        data class Poisoned(
            val failure: Throwable,
        ) : InstallResult
    }

    private fun interface PendingDestruction {
        fun awaitHandle(): Long
    }

    private val lock = Any()
    private val transitionGate = ReentrantLock(true)
    private val transitionChanged = transitionGate.newCondition()
    private var transitionOwner: Thread? = null
    private var transitionDepth = 0
    private var activeCallbacks = 0
    private val callbackDepth = ThreadLocal.withInitial { 0 }
    private val retired = ArrayDeque<PendingDestruction>()
    private val failedRetirements = ArrayDeque<PendingDestruction>()
    private var destructionFailure: Throwable? = null
    private var destructionInProgress = false
    private var activeDrain: CompletableFuture<Int>? = null

    fun beginOpen(): Long = beginOpenLocked(null)

    fun beginOpenIfCurrent(expectedSourceGeneration: Long): Long = beginOpenLocked(expectedSourceGeneration)

    fun <T> withSourceTransition(block: () -> T): T {
        enterSourceTransition()
        return try {
            block()
        } finally {
            exitSourceTransition()
        }
    }

    private fun beginOpenLocked(expectedSourceGeneration: Long?): Long {
        val generation =
            withSourceTransition {
                synchronized(lock) {
                    if (destructionFailure != null) return@synchronized 0L
                    if (
                        expectedSourceGeneration != null &&
                        !generations.isCurrent(expectedSourceGeneration)
                    ) {
                        return@synchronized 0L
                    }
                    val nextGeneration = generations.advance()
                    gate.retire()?.let(::enqueueRetirementLocked)
                    nextGeneration
                }
            }
        if (generation != 0L) afterGenerationAdvanced?.invoke()
        return generation
    }

    fun tryInstall(
        sourceGeneration: Long,
        handle: Long,
    ): InstallResult =
        synchronized(lock) {
            destructionFailure?.let { return@synchronized InstallResult.Poisoned(it) }
            if (!generations.isCurrent(sourceGeneration)) return@synchronized InstallResult.StaleOrClosed
            activeDrain?.let { return@synchronized InstallResult.WaitForDrain(it) }
            if (gate.install(handle, sourceGeneration)) {
                InstallResult.Installed
            } else {
                InstallResult.StaleOrClosed
            }
        }

    fun install(
        sourceGeneration: Long,
        handle: Long,
    ): Boolean = tryInstall(sourceGeneration, handle) == InstallResult.Installed

    fun <T> withPlayer(
        sourceGeneration: Long,
        block: (Long) -> T,
    ): T? {
        val lease =
            synchronized(lock) {
                if (destructionFailure != null || destructionInProgress) null else gate.acquire(sourceGeneration)
            } ?: return null
        return lease.use(block)
    }

    fun isCurrent(sourceGeneration: Long): Boolean = generations.isCurrent(sourceGeneration)

    fun capture(): Long = generations.capture()

    internal fun retirePlayerForTest() {
        val retirement = synchronized(lock) { gate.retire()?.also(::enqueueRetirementLocked) }
        retirement?.awaitHandle()
    }

    fun publish(
        sourceGeneration: Long,
        publication: () -> Unit,
    ): Boolean = generations.publishIfCurrent(sourceGeneration, publication)

    fun invokeCallback(
        sourceGeneration: Long,
        block: () -> Unit,
    ): Boolean {
        if (!enterCallback(sourceGeneration)) return false
        return try {
            block()
            true
        } finally {
            exitCallback()
        }
    }

    fun isCallbackActiveOnCurrentThread(): Boolean = callbackDepth.get() > 0

    fun close() {
        withSourceTransition {
            synchronized(lock) {
                generations.close()
                gate.close()?.let(::enqueueRetirementLocked)
            }
        }
    }

    fun destroyRejected(
        handle: Long,
        dispose: (Long) -> Unit,
    ) {
        if (handle == 0L) return
        synchronized(lock) { retired.addLast(PendingDestruction { handle }) }
        rejectedEnqueuedForTest?.invoke(handle)
        drainRetired(dispose)
    }

    private fun enqueueRetirementLocked(retirement: LinuxNativePlayerGate.Retirement) {
        retired.addLast(PendingDestruction(retirement::awaitHandle))
    }

    private fun enterSourceTransition() {
        val current = Thread.currentThread()
        var reportedWaiting = false
        while (true) {
            var reportWaiting = false
            transitionGate.withLock {
                if (transitionOwner === current) {
                    transitionDepth += 1
                    return
                }
                val ownedCallbacks = callbackDepth.get()
                if (transitionOwner == null && activeCallbacks <= ownedCallbacks) {
                    transitionOwner = current
                    transitionDepth = 1
                    return
                }
                if (!reportedWaiting) {
                    reportedWaiting = true
                    reportWaiting = true
                } else {
                    transitionChanged.await()
                }
            }
            if (reportWaiting) {
                try {
                    transitionWaitingForCallbacksForTest?.invoke()
                } catch (_: Throwable) {
                    // Diagnostic observers must not abort mandatory callback-transition waits.
                }
            }
        }
    }

    private fun exitSourceTransition() {
        transitionGate.withLock {
            check(transitionOwner === Thread.currentThread() && transitionDepth > 0)
            transitionDepth -= 1
            if (transitionDepth == 0) {
                transitionOwner = null
                transitionChanged.signalAll()
            }
        }
    }

    private fun enterCallback(sourceGeneration: Long): Boolean {
        val current = Thread.currentThread()
        transitionGate.withLock {
            while (transitionOwner != null && transitionOwner !== current) transitionChanged.await()
            if (!generations.isCurrent(sourceGeneration)) return false
            activeCallbacks += 1
            callbackDepth.set(callbackDepth.get() + 1)
            return true
        }
    }

    private fun exitCallback() {
        transitionGate.withLock {
            val depth = callbackDepth.get()
            check(depth > 0 && activeCallbacks > 0)
            if (depth == 1) callbackDepth.remove() else callbackDepth.set(depth - 1)
            activeCallbacks -= 1
            transitionChanged.signalAll()
        }
    }

    fun drainRetired(dispose: (Long) -> Unit): Int {
        val completion: CompletableFuture<Int>
        val owner: Boolean
        synchronized(lock) {
            val running = activeDrain
            if (running != null) {
                completion = running
                owner = false
            } else {
                if (retired.isEmpty()) {
                    destructionFailure?.let { throw it }
                    return 0
                }
                completion = CompletableFuture()
                activeDrain = completion
                destructionInProgress = true
                owner = true
            }
        }

        if (!owner) return awaitDrain(completion)

        var drained = 0
        var firstFailure: Throwable? = synchronized(lock) { destructionFailure }
        var firstInterruption: InterruptedException? = null
        while (true) {
            val claimed =
                synchronized(lock) {
                    buildList {
                        while (retired.isNotEmpty()) add(retired.removeFirst())
                    }
                }

            val failed = mutableListOf<PendingDestruction>()
            claimed.forEach { retirement ->
                var handle: Long? = null
                while (handle == null) {
                    try {
                        handle = retirement.awaitHandle()
                    } catch (interrupted: InterruptedException) {
                        if (firstInterruption == null) firstInterruption = interrupted
                        try {
                            retirementInterruptedForTest?.invoke()
                        } catch (_: Throwable) {
                            // Diagnostic observers must not abort mandatory retirement retry.
                        }
                    }
                }
                try {
                    dispose(requireNotNull(handle))
                } catch (failure: Throwable) {
                    failed += retirement
                    if (firstFailure == null) firstFailure = failure
                }
            }
            drained += claimed.size

            val finished =
                synchronized(lock) {
                    failedRetirements.addAll(failed)
                    if (destructionFailure == null && firstFailure != null) destructionFailure = firstFailure
                    if (retired.isEmpty()) {
                        check(activeDrain === completion)
                        activeDrain = null
                        destructionInProgress = false
                        true
                    } else {
                        false
                    }
                }
            if (finished) break
        }

        val interruption = firstInterruption
        val failure =
            firstFailure?.also { destruction ->
                if (interruption != null && destruction !== interruption) destruction.addSuppressed(interruption)
            } ?: interruption
        if (failure == null) {
            completion.complete(drained)
            return drained
        }
        completion.completeExceptionally(failure)
        throw failure
    }

    private fun awaitDrain(completion: CompletableFuture<Int>): Int =
        try {
            completion.join()
        } catch (failure: CompletionException) {
            throw failure.cause ?: failure
        }
}
