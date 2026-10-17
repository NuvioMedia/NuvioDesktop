package io.github.kdroidfilter.composemediaplayer.linux

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal enum class DisposalWaitBoundary {
    CREATION_QUIESCENCE,
    OPEN_JOBS,
    INITIALIZATION,
    OPERATION_ROOT,
}

internal class LinuxDisposalFailureCollector {
    private val failures = mutableListOf<Throwable>()

    fun record(failure: Throwable) {
        failures += failure
    }

    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            record(failure)
        }
    }

    fun snapshot(): List<Throwable> = failures.toList()
}

internal class LinuxDisposalExecution(
    private val completion: CompletableFuture<Unit>,
    private val fallbackLauncher: (Runnable) -> Unit = { fallback ->
        Thread(fallback, "linux-player-disposal-fallback").apply { isDaemon = true }.start()
    },
    private val cleanup: (LinuxDisposalFailureCollector) -> Unit,
) : Runnable {
    private val claimed = AtomicBoolean(false)
    private val stateLock = Any()
    private var submissionResolved = false
    private var submissionFailure: Throwable? = null
    private var fallbackLaunchFailure: Throwable? = null
    private var fallbackLaunchResolved = true
    private var workerResolved = false
    private var workerFailure: Throwable? = null
    private var cleanupResolved = false
    private var cleanupFailures: List<Throwable> = emptyList()
    private var terminalPublished = false

    override fun run() {
        if (!claimed.compareAndSet(false, true)) return
        val failures = LinuxDisposalFailureCollector()
        try {
            cleanup(failures)
        } catch (failure: Throwable) {
            failures.record(failure)
        } finally {
            synchronized(stateLock) {
                cleanupFailures = failures.snapshot()
                cleanupResolved = true
            }
            publishIfTerminal()
        }
    }

    fun submissionFailed(failure: Throwable) {
        synchronized(stateLock) {
            check(!submissionResolved)
            submissionResolved = true
            submissionFailure = failure
            workerResolved = true
            fallbackLaunchResolved = false
        }
        try {
            fallbackLauncher(this)
            synchronized(stateLock) {
                fallbackLaunchResolved = true
            }
        } catch (fallbackFailure: Throwable) {
            synchronized(stateLock) {
                fallbackLaunchFailure = fallbackFailure
                fallbackLaunchResolved = true
            }
            // There is no remaining executor to rely on. Claim and perform cleanup here; run()'s
            // one-shot guard also covers launchers that scheduled this Runnable before throwing.
            run()
        }
        publishIfTerminal()
    }

    fun submissionSucceeded(worker: CompletableFuture<Void>) {
        synchronized(stateLock) {
            check(!submissionResolved)
            submissionResolved = true
        }
        worker.whenComplete { _, throwable ->
            synchronized(stateLock) {
                workerFailure = unwrapDisposalFailure(throwable)
                workerResolved = true
            }
            run()
            publishIfTerminal()
        }
        publishIfTerminal()
    }

    private fun publishIfTerminal() {
        val failures =
            synchronized(stateLock) {
                if (
                    terminalPublished ||
                    !submissionResolved ||
                    !workerResolved ||
                    !fallbackLaunchResolved ||
                    !cleanupResolved
                ) return
                terminalPublished = true
                buildList {
                    submissionFailure?.let(::add)
                    fallbackLaunchFailure?.let(::add)
                    workerFailure?.let(::add)
                    addAll(cleanupFailures)
                }
            }
        if (failures.isEmpty()) {
            completion.complete(Unit)
        } else {
            val primary = failures.first()
            failures.drop(1).forEach { failure ->
                if (failure !== primary) primary.addSuppressed(failure)
            }
            completion.completeExceptionally(primary)
        }
    }
}

internal fun unwrapDisposalFailure(failure: Throwable?): Throwable? =
    when (failure) {
        is CompletionException, is ExecutionException -> failure.cause ?: failure
        else -> failure
    }
