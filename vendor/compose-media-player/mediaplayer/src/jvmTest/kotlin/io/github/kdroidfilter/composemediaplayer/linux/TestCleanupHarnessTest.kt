package io.github.kdroidfilter.composemediaplayer.linux

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TestCleanupHarnessTest {
    @Test
    fun primaryFailureIsPreservedAndCleanupFailureIsSuppressed() {
        val primary = AssertionError("primary")
        val cleanup = IllegalStateException("cleanup")
        val harness = TestCleanupHarness()

        val thrown =
            assertFailsWith<AssertionError> {
                runBlocking {
                    harness.run {
                        harness.defer { throw cleanup }
                        throw primary
                    }
                }
            }

        assertSame(primary, thrown)
        assertSame(cleanup, thrown.suppressed.single())
    }

    @Test
    fun cleanupFailureIsSurfacedWhenTestBodySucceeds() {
        val cleanup = IllegalStateException("cleanup")
        val harness = TestCleanupHarness()

        val thrown =
            assertFailsWith<IllegalStateException> {
                runBlocking {
                    harness.run {
                        harness.defer { throw cleanup }
                    }
                }
            }

        assertSame(cleanup, thrown)
    }
}
