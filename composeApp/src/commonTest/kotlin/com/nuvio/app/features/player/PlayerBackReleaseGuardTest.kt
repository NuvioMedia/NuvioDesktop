package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerBackReleaseGuardTest {
    private class GuardFatalError : Error("fatal guard failure")

    @Test
    fun canStartThrowableIsContainedAndLaterRequestCanProceed() {
        val guard = PlayerBackReleaseGuard()
        var canStartAttempts = 0
        var releases = 0

        repeat(2) {
            guard.request(
                canStart = {
                    canStartAttempts += 1
                    if (canStartAttempts == 1) throw GuardFatalError()
                    true
                },
                releaseBeforeBack = { _, _ -> releases += 1 },
                beforePop = {},
                pop = { true },
            )
        }

        assertEquals(2, canStartAttempts)
        assertEquals(1, releases)
    }

    @Test
    fun releaseStartupThrowableAllowsRetry() {
        val guard = PlayerBackReleaseGuard()
        var attempts = 0
        var pops = 0

        repeat(2) {
            guard.request(
                canStart = { true },
                releaseBeforeBack = { onReleased, _ ->
                    attempts += 1
                    if (attempts == 1) throw GuardFatalError()
                    onReleased()
                },
                beforePop = {},
                pop = { pops += 1; true },
            )
        }

        assertEquals(2, attempts)
        assertEquals(1, pops)
    }

    @Test
    fun completionThrowableAllowsRetry() {
        val guard = PlayerBackReleaseGuard()
        var beforePopAttempts = 0
        var pops = 0

        repeat(2) {
            guard.request(
                canStart = { true },
                releaseBeforeBack = { onReleased, _ -> onReleased() },
                beforePop = {
                    beforePopAttempts += 1
                    if (beforePopAttempts == 1) throw GuardFatalError()
                },
                pop = { pops += 1; true },
            )
        }

        assertEquals(2, beforePopAttempts)
        assertEquals(1, pops)
    }

    @Test
    fun asynchronousReleaseFailureAllowsRetry() {
        val guard = PlayerBackReleaseGuard()
        var failRelease: ((String) -> Unit)? = null
        var releases = 0

        guard.request(
            canStart = { true },
            releaseBeforeBack = { _, onFailed ->
                releases += 1
                failRelease = onFailed
            },
            beforePop = {},
            pop = { true },
        )
        failRelease?.invoke("timed out")
        guard.request(
            canStart = { true },
            releaseBeforeBack = { _, _ -> releases += 1 },
            beforePop = {},
            pop = { true },
        )

        assertEquals(2, releases)
    }

    @Test
    fun synchronousReleaseFailureAllowsRetry() {
        val guard = PlayerBackReleaseGuard()
        var attempts = 0
        var pops = 0

        guard.request(
            canStart = { true },
            releaseBeforeBack = { onReleased, _ ->
                attempts += 1
                if (attempts == 1) error("release failed")
                onReleased()
            },
            beforePop = {},
            pop = { pops += 1; true },
        )
        guard.request(
            canStart = { true },
            releaseBeforeBack = { onReleased, _ -> attempts += 1; onReleased() },
            beforePop = {},
            pop = { pops += 1; true },
        )

        assertEquals(2, attempts)
        assertEquals(1, pops)
    }

    @Test
    fun completionFailureAllowsRetry() {
        val guard = PlayerBackReleaseGuard()
        var beforePopAttempts = 0
        var pops = 0

        repeat(2) {
            guard.request(
                canStart = { true },
                releaseBeforeBack = { onReleased, _ -> onReleased() },
                beforePop = {
                    beforePopAttempts += 1
                    if (beforePopAttempts == 1) error("before pop failed")
                },
                pop = { pops += 1; true },
            )
        }

        assertEquals(2, beforePopAttempts)
        assertEquals(1, pops)
    }

    @Test
    fun staleSuccessFromFailedAttemptCannotPopDuringRetry() {
        val guard = PlayerBackReleaseGuard()
        val attempts = mutableListOf<Pair<() -> Unit, (String) -> Unit>>()
        var pops = 0

        fun requestBack() {
            guard.request(
                canStart = { true },
                releaseBeforeBack = { onReleased, onReleaseFailed ->
                    attempts += onReleased to onReleaseFailed
                },
                beforePop = {},
                pop = { pops += 1; true },
            )
        }

        requestBack()
        attempts[0].second("timed out")
        requestBack()

        attempts[0].first()
        assertEquals(0, pops)

        attempts[1].first()
        assertEquals(1, pops)
    }

    @Test
    fun duplicateSuccessCallbackPopsExactlyOnce() {
        val guard = PlayerBackReleaseGuard()
        lateinit var completeRelease: () -> Unit
        var pops = 0

        guard.request(
            canStart = { true },
            releaseBeforeBack = { onReleased, _ -> completeRelease = onReleased },
            beforePop = {},
            pop = { pops += 1; true },
        )

        completeRelease()
        completeRelease()

        assertEquals(1, pops)
    }
}
