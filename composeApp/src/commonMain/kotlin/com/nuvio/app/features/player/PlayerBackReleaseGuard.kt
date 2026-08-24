package com.nuvio.app.features.player

internal fun dispatchNavigationBack(
    isPlayerRoute: Boolean,
    playerBack: (() -> Unit)?,
    pop: () -> Unit,
) {
    if (isPlayerRoute) {
        playerBack?.invoke()
    } else {
        pop()
    }
}

/**
 * Serializes release-before-navigation attempts.
 *
 * Every synchronous callback boundary deliberately contains [Throwable]: even an [Error]
 * must not escape after [inFlight] changes and leave Back permanently wedged. Each accepted
 * request also owns a single terminal callback claim, so late or duplicate callbacks cannot act
 * on a retry or navigate twice. Failures keep navigation closed and restore retryability where a
 * retry is still safe.
 */
internal class PlayerBackReleaseGuard {
    private var inFlight: Boolean = false
    private var nextAttemptId: Long = 0L
    private var activeAttemptId: Long? = null

    fun request(
        canStart: () -> Boolean,
        releaseBeforeBack: (
            onReleased: () -> Unit,
            onReleaseFailed: (String) -> Unit,
        ) -> Unit,
        beforePop: () -> Unit,
        pop: () -> Boolean,
    ) {
        if (inFlight) return
        val allowed = try {
            canStart()
        } catch (_: Throwable) {
            // Deliberately contain synchronous callback failures so the Back guard
            // remains fail-closed instead of escaping with partially updated state.
            false
        }
        if (!allowed) return

        inFlight = true
        val attemptId = ++nextAttemptId
        activeAttemptId = attemptId

        fun claimActiveAttempt(): Boolean {
            if (activeAttemptId != attemptId) return false
            activeAttemptId = null
            return true
        }

        val complete = complete@{
            if (!claimActiveAttempt()) return@complete
            try {
                beforePop()
                if (!pop()) inFlight = false
            } catch (_: Throwable) {
                inFlight = false
            }
        }
        val fail = fail@{ _: String ->
            if (!claimActiveAttempt()) return@fail
            inFlight = false
        }
        try {
            releaseBeforeBack(complete, fail)
        } catch (_: Throwable) {
            if (claimActiveAttempt()) inFlight = false
        }
    }
}
