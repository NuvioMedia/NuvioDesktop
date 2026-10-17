package io.github.kdroidfilter.composemediaplayer.linux

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxNativePlayerGateTest {
    @Test
    fun `leases require the installed source generation`() {
        val gate = LinuxNativePlayerGate()
        assertTrue(gate.install(handle = 42L, sourceGeneration = 7L))

        assertNull(gate.withPlayer(expectedSourceGeneration = 6L) { it })
        assertEquals(42L, gate.withPlayer(expectedSourceGeneration = 7L) { it })
    }

    @Test
    fun `retire revokes synchronously and waits for an active lease outside the bookkeeping lock`() {
        val retirementWaiting = CountDownLatch(1)
        val gate = LinuxNativePlayerGate(retirementWaitingForLeasesForTest = retirementWaiting::countDown)
        assertTrue(gate.install(handle = 42L, sourceGeneration = 7L))
        val leaseEntered = CountDownLatch(1)
        val releaseLease = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val caller =
                executor.submit<Long?> {
                    gate.withPlayer(expectedSourceGeneration = 7L) { handle ->
                        leaseEntered.countDown()
                        check(releaseLease.await(5, TimeUnit.SECONDS))
                        handle
                    }
                }
            assertTrue(leaseEntered.await(5, TimeUnit.SECONDS))

            val retirement = gate.retire()
            assertNull(gate.withPlayer(expectedSourceGeneration = 7L) { it })
            val waiter = executor.submit<Long> { retirement!!.awaitHandle() }
            assertTrue(retirementWaiting.await(5, TimeUnit.SECONDS))

            releaseLease.countDown()
            assertEquals(42L, caller.get(5, TimeUnit.SECONDS))
            assertEquals(42L, waiter.get(5, TimeUnit.SECONDS))
        } finally {
            releaseLease.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `diagnostic observer throwable does not abort retirement wait`() {
        val observerCalled = CountDownLatch(1)
        val gate =
            LinuxNativePlayerGate(
                retirementWaitingForLeasesForTest = {
                    observerCalled.countDown()
                    throw AssertionError("diagnostic observer failure")
                },
            )
        assertTrue(gate.install(handle = 42L, sourceGeneration = 7L))
        val leaseEntered = CountDownLatch(1)
        val releaseLease = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val caller =
                executor.submit<Long?> {
                    gate.withPlayer(expectedSourceGeneration = 7L) { handle ->
                        leaseEntered.countDown()
                        check(releaseLease.await(5, TimeUnit.SECONDS))
                        handle
                    }
                }
            assertTrue(leaseEntered.await(5, TimeUnit.SECONDS))

            val retirement = requireNotNull(gate.retire())
            val waiter = executor.submit<Long> { retirement.awaitHandle() }
            assertTrue(observerCalled.await(5, TimeUnit.SECONDS))
            assertFalse(waiter.isDone)

            releaseLease.countDown()
            assertEquals(42L, caller.get(5, TimeUnit.SECONDS))
            assertEquals(42L, waiter.get(5, TimeUnit.SECONDS))
        } finally {
            releaseLease.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `close permanently rejects later player installation`() {
        val gate = LinuxNativePlayerGate()
        assertTrue(gate.install(handle = 42L, sourceGeneration = 7L))

        assertEquals(42L, gate.close()!!.awaitHandle())

        assertFalse(gate.install(handle = 84L, sourceGeneration = 8L))
        assertNull(gate.withPlayer(expectedSourceGeneration = 8L) { it })
    }
}
