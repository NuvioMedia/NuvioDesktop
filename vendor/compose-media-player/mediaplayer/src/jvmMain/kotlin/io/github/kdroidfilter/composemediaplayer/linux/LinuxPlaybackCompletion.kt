package io.github.kdroidfilter.composemediaplayer.linux

/**
 * Owns the durable end-of-playback state needed after the one-shot native EOS
 * notification has been consumed by the UI update loop.
 *
 * Frame-update jobs carry the generation they started under so a late EOS from
 * a cancelled job cannot mark replacement media as ended. No callback or native
 * operation is ever executed while [lock] is held.
 */
internal class LinuxPlaybackCompletion {
    private val lock = Any()
    private var generation = 0L
    private var endedGeneration: Long? = null

    fun captureGeneration(): Long = synchronized(lock) { generation }

    fun isCurrent(observedGeneration: Long): Boolean =
        synchronized(lock) { generation == observedGeneration }

    fun markEnded(observedGeneration: Long): Boolean =
        synchronized(lock) {
            if (generation != observedGeneration) {
                false
            } else {
                endedGeneration = observedGeneration
                true
            }
        }

    fun replayGeneration(): Long? =
        synchronized(lock) {
            endedGeneration?.takeIf { it == generation }
        }

    fun completeReplay(observedGeneration: Long): Boolean =
        synchronized(lock) {
            if (generation != observedGeneration || endedGeneration != observedGeneration) {
                false
            } else {
                endedGeneration = null
                generation += 1
                true
            }
        }

    fun reset() {
        synchronized(lock) {
            generation += 1
            endedGeneration = null
        }
    }
}

/**
 * Serializes native playback commands and completion callbacks without holding
 * [LinuxPlaybackCompletion]'s state lock across arbitrary or JNI work.
 */
internal class LinuxPlaybackResumeCoordinator(
    private val completion: LinuxPlaybackCompletion,
) {
    private val commandLock = Any()

    fun markEndedIfConsumed(
        observedGeneration: Long,
        consumeEnd: () -> Boolean,
    ): Boolean =
        synchronized(commandLock) {
            if (!completion.isCurrent(observedGeneration) || !consumeEnd()) {
                false
            } else {
                completion.markEnded(observedGeneration)
            }
        }

    fun runIfCurrent(
        observedGeneration: Long,
        action: () -> Unit,
    ): Boolean =
        synchronized(commandLock) {
            if (!completion.isCurrent(observedGeneration)) {
                false
            } else {
                action()
                true
            }
        }

    fun runCommand(action: () -> Unit) {
        synchronized(commandLock) { action() }
    }

    fun resume(
        seekToStart: () -> Unit,
        play: () -> Unit,
    ) {
        synchronized(commandLock) {
            val replayGeneration = completion.replayGeneration()
            if (replayGeneration == null) {
                play()
                return
            }

            // Commit only after both native operations succeed. If either throws,
            // the durable marker remains available for the next Play retry.
            seekToStart()
            play()
            check(completion.completeReplay(replayGeneration)) {
                "Replay generation changed while playback commands were serialized"
            }
        }
    }

    fun reset() {
        resetAndRun {}
    }

    fun resetAndRun(action: () -> Unit) {
        synchronized(commandLock) {
            completion.reset()
            action()
        }
    }
}
