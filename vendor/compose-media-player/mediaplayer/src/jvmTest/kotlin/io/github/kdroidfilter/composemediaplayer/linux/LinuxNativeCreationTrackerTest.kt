package io.github.kdroidfilter.composemediaplayer.linux

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxNativeCreationTrackerTest {
    @Test
    fun `diagnostic observer throwable does not abort creation quiescence wait`() {
        val observerCalled = CountDownLatch(1)
        val tracker =
            LinuxNativeCreationTracker {
                observerCalled.countDown()
                throw AssertionError("diagnostic observer failure")
            }
        val creationEntered = CountDownLatch(1)
        val releaseCreation = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val creation =
                executor.submit {
                    runBlocking {
                        tracker.track {
                            creationEntered.countDown()
                            check(releaseCreation.await(5, TimeUnit.SECONDS))
                        }
                    }
                }
            assertTrue(creationEntered.await(5, TimeUnit.SECONDS))

            tracker.close()
            val waiter = executor.submit { tracker.awaitQuiescence() }
            assertTrue(observerCalled.await(5, TimeUnit.SECONDS))
            assertFalse(waiter.isDone)

            releaseCreation.countDown()
            creation.get(5, TimeUnit.SECONDS)
            waiter.get(5, TimeUnit.SECONDS)
        } finally {
            releaseCreation.countDown()
            executor.shutdownNow()
        }
    }
}
