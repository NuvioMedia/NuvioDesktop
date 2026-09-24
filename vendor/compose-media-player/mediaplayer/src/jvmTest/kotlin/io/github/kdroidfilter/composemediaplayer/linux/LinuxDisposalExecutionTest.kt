package io.github.kdroidfilter.composemediaplayer.linux

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinuxDisposalExecutionTest {
    @Test
    fun fallbackLaunchFailureRunsCleanupExactlyOnceAndPreservesLaunchFailures() {
        val completion = CompletableFuture<Unit>()
        val rejection = RejectedExecutionException("worker rejected")
        val fallbackFailure = IllegalStateException("fallback rejected")
        val cleanupCount = AtomicInteger()
        val execution =
            LinuxDisposalExecution(
                completion,
                cleanup = { cleanupCount.incrementAndGet() },
                fallbackLauncher = { throw fallbackFailure },
            )

        execution.submissionFailed(rejection)

        val failure = kotlin.runCatching { completion.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertSame(rejection, failure?.cause)
        assertTrue(rejection.suppressed.contains(fallbackFailure))
        assertEquals(1, cleanupCount.get())
    }

    @Test
    fun fallbackThatRunsThenThrowsStillPreservesFailureAndRunsCleanupOnce() {
        val completion = CompletableFuture<Unit>()
        val rejection = RejectedExecutionException("worker rejected")
        val fallbackFailure = IllegalStateException("fallback scheduled then failed")
        val cleanupCount = AtomicInteger()
        val execution =
            LinuxDisposalExecution(
                completion,
                cleanup = { cleanupCount.incrementAndGet() },
                fallbackLauncher = { fallback ->
                    fallback.run()
                    throw fallbackFailure
                },
            )

        execution.submissionFailed(rejection)

        val failure = kotlin.runCatching { completion.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertSame(rejection, failure?.cause)
        assertTrue(rejection.suppressed.contains(fallbackFailure))
        assertEquals(1, cleanupCount.get())
    }

    @Test
    fun submissionFailureRunsFallbackCleanupOffTheSubmittingThread() {
        val completion = CompletableFuture<Unit>()
        val submittingThread = Thread.currentThread()
        val cleanupThread = AtomicReference<Thread>()
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val rejection = RejectedExecutionException("injected rejection")
        val execution =
            LinuxDisposalExecution(completion) {
                cleanupThread.set(Thread.currentThread())
                cleanupEntered.countDown()
                check(releaseCleanup.await(5, TimeUnit.SECONDS))
            }

        try {
            execution.submissionFailed(rejection)

            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
            assertFalse(cleanupThread.get() === submittingThread)
            assertFalse(completion.isDone)
            releaseCleanup.countDown()
            val failure = kotlin.runCatching { completion.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
            assertSame(rejection, failure?.cause)
        } finally {
            releaseCleanup.countDown()
        }
    }

    @Test
    fun scheduleThenThrowWaitsForTheUniqueCleanupOwner() {
        val completion = CompletableFuture<Unit>()
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleanupCount = AtomicInteger()
        val rejection = RejectedExecutionException("scheduled then rejected")
        val execution =
            LinuxDisposalExecution(completion) {
                cleanupCount.incrementAndGet()
                cleanupEntered.countDown()
                check(releaseCleanup.await(5, TimeUnit.SECONDS))
            }
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.execute(execution)
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))

            execution.submissionFailed(rejection)

            assertEquals(1, cleanupCount.get())
            assertFalse(completion.isDone)
            releaseCleanup.countDown()
            val failure = kotlin.runCatching { completion.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
            assertSame(rejection, failure?.cause)
            assertEquals(1, cleanupCount.get())
        } finally {
            releaseCleanup.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun failedReturnedWorkerRunsOneShotFallbackAndPreservesWorkerFailure() {
        val completion = CompletableFuture<Unit>()
        val cleanupCount = AtomicInteger()
        val workerFailure = IllegalStateException("worker failed before execution")
        val execution = LinuxDisposalExecution(completion) { cleanupCount.incrementAndGet() }
        val worker = CompletableFuture<Void>()
        worker.completeExceptionally(workerFailure)

        execution.submissionSucceeded(worker)

        val failure = kotlin.runCatching { completion.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertSame(workerFailure, failure?.cause)
        assertEquals(1, cleanupCount.get())
    }
}
