package io.github.kdroidfilter.composemediaplayer.linux

internal class TestCleanupHarness {
    private val cleanups = ArrayDeque<() -> Unit>()

    fun defer(cleanup: () -> Unit) {
        cleanups.addFirst(cleanup)
    }

    suspend fun run(block: suspend () -> Unit) {
        var primary: Throwable? = null
        try {
            block()
        } catch (failure: Throwable) {
            primary = failure
        }

        while (cleanups.isNotEmpty()) {
            try {
                cleanups.removeFirst().invoke()
            } catch (cleanupFailure: Throwable) {
                if (primary == null) {
                    primary = cleanupFailure
                } else if (cleanupFailure !== primary) {
                    primary.addSuppressed(cleanupFailure)
                }
            }
        }

        primary?.let { throw it }
    }
}
