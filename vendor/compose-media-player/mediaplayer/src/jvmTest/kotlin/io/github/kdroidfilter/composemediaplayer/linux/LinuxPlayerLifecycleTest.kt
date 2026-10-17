package io.github.kdroidfilter.composemediaplayer.linux

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxPlayerLifecycleTest {
    @Test
    fun `begin open synchronously invalidates the old handle and stale publications`() {
        val lifecycle = LinuxPlayerLifecycle()
        val first = lifecycle.beginOpen()
        assertTrue(lifecycle.install(first, 41L))
        var visible = "first"

        val second = lifecycle.beginOpen()

        assertNull(lifecycle.withPlayer(first) { it })
        assertFalse(lifecycle.publish(first) { visible = "stale" })
        assertTrue(lifecycle.publish(second) { visible = "opening" })
        assertEquals("opening", visible)
    }

    @Test
    fun `callback publication is final and can reentrantly advance source`() {
        val lifecycle = LinuxPlayerLifecycle()
        val first = lifecycle.beginOpen()
        var second = 0L

        assertTrue(lifecycle.invokeCallback(first) { second = lifecycle.beginOpen() })
        assertTrue(second > first)
        assertFalse(lifecycle.invokeCallback(first) { error("stale callback") })
    }

    @Test
    fun `generation transition waits for an active callback without holding lifecycle lock`() {
        val transitionWaiting = CountDownLatch(1)
        val lifecycle =
            LinuxPlayerLifecycle(
                transitionWaitingForCallbacksForTest = transitionWaiting::countDown,
            )
        val first = lifecycle.beginOpen()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val callback =
                executor.submit<Boolean> {
                    lifecycle.invokeCallback(first) {
                        callbackEntered.countDown()
                        check(releaseCallback.await(5, TimeUnit.SECONDS))
                    }
                }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            val transition = executor.submit<Long> { lifecycle.beginOpen() }
            assertTrue(transitionWaiting.await(5, TimeUnit.SECONDS))
            releaseCallback.countDown()
            assertTrue(callback.get(5, TimeUnit.SECONDS))
            assertTrue(transition.get(5, TimeUnit.SECONDS) > first)
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `diagnostic observer failure does not abort callback transition wait`() {
        val observerCalled = CountDownLatch(1)
        val lifecycle =
            LinuxPlayerLifecycle(
                transitionWaitingForCallbacksForTest = {
                    observerCalled.countDown()
                    throw AssertionError("diagnostic observer failure")
                },
            )
        val first = lifecycle.beginOpen()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val callback =
                executor.submit<Boolean> {
                    lifecycle.invokeCallback(first) {
                        callbackEntered.countDown()
                        check(releaseCallback.await(5, TimeUnit.SECONDS))
                    }
                }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))

            val transition = executor.submit<Long> { lifecycle.beginOpen() }
            assertTrue(observerCalled.await(5, TimeUnit.SECONDS))
            assertFalse(transition.isDone)

            releaseCallback.countDown()
            assertTrue(callback.get(5, TimeUnit.SECONDS))
            assertTrue(transition.get(5, TimeUnit.SECONDS) > first)
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `post transition callback runs after stale lease revocation and outside lifecycle lock`() {
        val transitionEntered = CountDownLatch(1)
        val releaseTransition = CountDownLatch(1)
        var advances = 0
        val lifecycle =
            LinuxPlayerLifecycle(
                afterGenerationAdvanced = {
                    advances += 1
                    if (advances == 2) {
                        transitionEntered.countDown()
                        check(releaseTransition.await(5, TimeUnit.SECONDS))
                    }
                },
            )
        val first = lifecycle.beginOpen()
        assertTrue(lifecycle.install(first, 41L))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val transition = executor.submit<Long> { lifecycle.beginOpen() }
            assertTrue(transitionEntered.await(5, TimeUnit.SECONDS))

            val staleLease = executor.submit<Long?> { lifecycle.withPlayer(first) { it } }
            assertNull(staleLease.get(5, TimeUnit.SECONDS))

            releaseTransition.countDown()
            assertTrue(transition.get(5, TimeUnit.SECONDS) > first)
        } finally {
            releaseTransition.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `replacement drains an active old handle exactly once without blocking begin open`() {
        val retirementWaiting = CountDownLatch(1)
        val lifecycle =
            LinuxPlayerLifecycle(
                gate = LinuxNativePlayerGate(retirementWaitingForLeasesForTest = retirementWaiting::countDown),
            )
        val first = lifecycle.beginOpen()
        assertTrue(lifecycle.install(first, 41L))
        val leaseEntered = CountDownLatch(1)
        val releaseLease = CountDownLatch(1)
        val disposed = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val caller =
                executor.submit<Long?> {
                    lifecycle.withPlayer(first) { handle ->
                        leaseEntered.countDown()
                        check(releaseLease.await(5, TimeUnit.SECONDS))
                        handle
                    }
                }
            assertTrue(leaseEntered.await(5, TimeUnit.SECONDS))

            val second = lifecycle.beginOpen()
            assertTrue(second > first)
            assertNull(lifecycle.withPlayer(second) { it })
            val drainer =
                executor.submit<Int> {
                    lifecycle.drainRetired { handle ->
                        assertEquals(41L, handle)
                        disposed.incrementAndGet()
                    }
                }
            assertTrue(retirementWaiting.await(5, TimeUnit.SECONDS))

            releaseLease.countDown()
            assertEquals(41L, caller.get(5, TimeUnit.SECONDS))
            assertEquals(1, drainer.get(5, TimeUnit.SECONDS))
            assertEquals(0, lifecycle.drainRetired { disposed.incrementAndGet() })
            assertEquals(1, disposed.get())
        } finally {
            releaseLease.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `interrupted retirement wait retries when its diagnostic observer throws`() {
        val retirementWaiting = CountDownLatch(1)
        val retirementRetryRegistered = CountDownLatch(1)
        val retirementRegistrationCount = AtomicInteger()
        val interruptionObserved = CountDownLatch(1)
        val lifecycle =
            LinuxPlayerLifecycle(
                gate =
                    LinuxNativePlayerGate(
                        retirementWaitingForLeasesForTest = {
                            when (retirementRegistrationCount.incrementAndGet()) {
                                1 -> retirementWaiting.countDown()
                                2 -> retirementRetryRegistered.countDown()
                            }
                        },
                    ),
                retirementInterruptedForTest = {
                    interruptionObserved.countDown()
                    throw AssertionError("diagnostic observer failure")
                },
            )
        val generation = lifecycle.beginOpen()
        assertTrue(lifecycle.install(generation, 41L))
        val leaseEntered = CountDownLatch(1)
        val releaseLease = CountDownLatch(1)
        val drainerThread = AtomicReference<Thread?>()
        val disposed = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val lease =
                executor.submit<Long?> {
                    lifecycle.withPlayer(generation) { handle ->
                        leaseEntered.countDown()
                        check(releaseLease.await(5, TimeUnit.SECONDS))
                        handle
                    }
                }
            assertTrue(leaseEntered.await(5, TimeUnit.SECONDS))
            lifecycle.beginOpen()
            val drainer =
                executor.submit<Int> {
                    drainerThread.set(Thread.currentThread())
                    lifecycle.drainRetired { disposed.incrementAndGet() }
                }
            assertTrue(retirementWaiting.await(5, TimeUnit.SECONDS))
            requireNotNull(drainerThread.get()).interrupt()
            assertTrue(interruptionObserved.await(5, TimeUnit.SECONDS))
            assertTrue(retirementRetryRegistered.await(5, TimeUnit.SECONDS))
            releaseLease.countDown()
            assertEquals(41L, lease.get(5, TimeUnit.SECONDS))
            val failure =
                assertFailsWith<java.util.concurrent.ExecutionException> {
                    drainer.get(5, TimeUnit.SECONDS)
                }
            assertTrue(failure.cause is InterruptedException)
            assertEquals(1, disposed.get())
        } finally {
            releaseLease.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancelled source operation leaves retirement for its replacement to drain`() {
        val lifecycle = LinuxPlayerLifecycle()
        val first = lifecycle.beginOpen()
        assertTrue(lifecycle.install(first, 41L))

        lifecycle.beginOpen()
        val replacement = lifecycle.beginOpen()
        val disposed = mutableListOf<Long>()

        assertEquals(1, lifecycle.drainRetired(disposed::add))
        assertEquals(listOf(41L), disposed)
        assertTrue(lifecycle.install(replacement, 82L))
    }

    @Test
    fun `drain attempts every claimed retirement before rethrowing disposal failure`() {
        val lifecycle = LinuxPlayerLifecycle()
        val first = lifecycle.beginOpen()
        assertTrue(lifecycle.install(first, 41L))
        val second = lifecycle.beginOpen()
        assertTrue(lifecycle.install(second, 82L))
        lifecycle.beginOpen()
        val attempted = mutableListOf<Long>()

        assertFailsWith<IllegalStateException> {
            lifecycle.drainRetired { handle ->
                attempted += handle
                if (handle == 41L) error("first disposal failed")
            }
        }
        assertEquals(listOf(41L, 82L), attempted)
    }

    @Test
    fun `destruction in flight rejects replacement installation and leases before failure is known`() {
        val lifecycle = LinuxPlayerLifecycle()
        val first = lifecycle.beginOpen()
        assertTrue(lifecycle.install(first, 41L))
        val replacement = lifecycle.beginOpen()
        val destructionEntered = CountDownLatch(1)
        val releaseDestruction = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val failure =
                executor.submit<Throwable?> {
                    runCatching {
                        lifecycle.drainRetired { handle ->
                            assertEquals(41L, handle)
                            destructionEntered.countDown()
                            check(releaseDestruction.await(5, TimeUnit.SECONDS))
                            error("destruction failed")
                        }
                    }.exceptionOrNull()
                }
            assertTrue(destructionEntered.await(5, TimeUnit.SECONDS))

            assertFalse(lifecycle.install(replacement, 82L))
            assertNull(lifecycle.withPlayer(replacement) { it })

            releaseDestruction.countDown()
            assertTrue(failure.get(5, TimeUnit.SECONDS) is IllegalStateException)
            assertEquals(0L, lifecycle.beginOpen())
            assertNull(lifecycle.withPlayer(replacement) { it })
        } finally {
            releaseDestruction.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `current replacement installation waits for retirement drain and then installs`() {
        val lifecycle = LinuxPlayerLifecycle()
        val first = lifecycle.beginOpen()
        assertTrue(lifecycle.install(first, 41L))
        val replacement = lifecycle.beginOpen()
        val destructionEntered = CountDownLatch(1)
        val releaseDestruction = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val drainer =
                executor.submit<Int> {
                    lifecycle.drainRetired { handle ->
                        assertEquals(41L, handle)
                        destructionEntered.countDown()
                        check(releaseDestruction.await(5, TimeUnit.SECONDS))
                    }
                }
            assertTrue(destructionEntered.await(5, TimeUnit.SECONDS))

            val waiting = lifecycle.tryInstall(replacement, 82L)
            assertTrue(waiting is LinuxPlayerLifecycle.InstallResult.WaitForDrain)
            assertNull(lifecycle.withPlayer(replacement) { it })

            releaseDestruction.countDown()
            assertEquals(1, drainer.get(5, TimeUnit.SECONDS))
            assertEquals(1, waiting.completion.get(5, TimeUnit.SECONDS))
            assertEquals(LinuxPlayerLifecycle.InstallResult.Installed, lifecycle.tryInstall(replacement, 82L))
            assertEquals(82L, lifecycle.withPlayer(replacement) { it })
        } finally {
            releaseDestruction.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `shared drain includes rejected handle enqueued during destruction before completion`() {
        val rejectedEnqueued = CountDownLatch(1)
        val lifecycle =
            LinuxPlayerLifecycle(
                rejectedEnqueuedForTest = { handle ->
                    if (handle == 82L) rejectedEnqueued.countDown()
                },
            )
        val first = lifecycle.beginOpen()
        assertTrue(lifecycle.install(first, 41L))
        val replacement = lifecycle.beginOpen()
        val firstDestructionEntered = CountDownLatch(1)
        val releaseFirstDestruction = CountDownLatch(1)

        val disposed = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val executor = Executors.newFixedThreadPool(2)
        val disposer: (Long) -> Unit = { handle ->
            disposed += handle
            if (handle == 41L) {
                firstDestructionEntered.countDown()
                check(releaseFirstDestruction.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            val owner = executor.submit<Int> { lifecycle.drainRetired(disposer) }
            assertTrue(firstDestructionEntered.await(5, TimeUnit.SECONDS))
            val follower =
                executor.submit<Unit> {
                    lifecycle.destroyRejected(82L, disposer)
                }
            assertTrue(rejectedEnqueued.await(5, TimeUnit.SECONDS))

            val waiting = lifecycle.tryInstall(replacement, 123L)
            assertTrue(waiting is LinuxPlayerLifecycle.InstallResult.WaitForDrain)

            releaseFirstDestruction.countDown()
            assertEquals(2, owner.get(5, TimeUnit.SECONDS))
            follower.get(5, TimeUnit.SECONDS)
            assertEquals(2, waiting.completion.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(41L, 82L), disposed.toList())
        } finally {
            releaseFirstDestruction.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `drain records poison and attempts all handles before shared failure completion`() {
        val lifecycle = LinuxPlayerLifecycle()
        val first = lifecycle.beginOpen()
        assertTrue(lifecycle.install(first, 41L))
        val second = lifecycle.beginOpen()
        assertTrue(lifecycle.install(second, 82L))
        val replacement = lifecycle.beginOpen()
        val firstDestructionEntered = CountDownLatch(1)
        val releaseFirstDestruction = CountDownLatch(1)
        val callbackCompleted = CountDownLatch(1)
        val callbackFailure =
            java.util.concurrent.atomic
                .AtomicReference<Throwable?>()
        val attempted = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val drainer =
                executor.submit<Throwable?> {
                    runCatching {
                        lifecycle.drainRetired { handle ->
                            attempted += handle
                            if (handle == 41L) {
                                firstDestructionEntered.countDown()
                                check(releaseFirstDestruction.await(5, TimeUnit.SECONDS))
                                error("destruction failed")
                            }
                        }
                    }.exceptionOrNull()
                }
            assertTrue(firstDestructionEntered.await(5, TimeUnit.SECONDS))
            val waiting = lifecycle.tryInstall(replacement, 123L)
            assertTrue(waiting is LinuxPlayerLifecycle.InstallResult.WaitForDrain)
            waiting.completion.whenComplete { _, _ ->
                try {
                    val poisoned = lifecycle.tryInstall(replacement, 123L)
                    check(poisoned is LinuxPlayerLifecycle.InstallResult.Poisoned)
                    check(lifecycle.withPlayer(replacement) { it } == null)
                    check(attempted.toList() == listOf(41L, 82L))
                } catch (failure: Throwable) {
                    callbackFailure.set(failure)
                } finally {
                    callbackCompleted.countDown()
                }
            }

            releaseFirstDestruction.countDown()
            assertTrue(drainer.get(5, TimeUnit.SECONDS) is IllegalStateException)
            assertTrue(callbackCompleted.await(5, TimeUnit.SECONDS))
            assertNull(callbackFailure.get())
        } finally {
            releaseFirstDestruction.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `generation exhaustion revokes and retires the installed handle`() {
        val lifecycle =
            LinuxPlayerLifecycle(
                generations = LinuxPlayerGeneration(initialGeneration = Long.MAX_VALUE - 1L),
            )
        val lastGeneration = lifecycle.beginOpen()
        assertEquals(Long.MAX_VALUE, lastGeneration)
        assertTrue(lifecycle.install(lastGeneration, 41L))

        assertEquals(0L, lifecycle.beginOpen())

        assertNull(lifecycle.withPlayer(lastGeneration) { it })
        val disposed = mutableListOf<Long>()
        assertEquals(1, lifecycle.drainRetired(disposed::add))
        assertEquals(listOf(41L), disposed)
    }

    @Test
    fun `dispose rejects installation and drains the current handle once`() {
        val lifecycle = LinuxPlayerLifecycle()
        val generation = lifecycle.beginOpen()
        assertTrue(lifecycle.install(generation, 41L))

        lifecycle.close()
        val disposed = mutableListOf<Long>()

        assertFalse(lifecycle.install(generation, 82L))
        assertEquals(1, lifecycle.drainRetired(disposed::add))
        assertEquals(listOf(41L), disposed)
        assertEquals(0, lifecycle.beginOpen())
    }
}
