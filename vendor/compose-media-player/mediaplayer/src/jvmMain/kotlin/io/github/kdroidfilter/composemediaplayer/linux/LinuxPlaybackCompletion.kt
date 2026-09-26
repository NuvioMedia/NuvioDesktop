package io.github.kdroidfilter.composemediaplayer.linux

/**
 * Owns the durable end-of-playback state needed after the one-shot native EOS
 * notification has been consumed by the UI update loop.
 *
 * Frame-update jobs carry the generation they started under so a late EOS from
 * a cancelled job cannot mark replacement media as ended. No callback or native
 * operation is ever executed while [lock] is held.
 */
internal class LinuxPlaybackCompletion(
    initialGeneration: Long = 0L,
) {
    private val lock = Any()
    private var generation = initialGeneration
    private var endedGeneration: Long? = null
    private var exhausted = false

    fun captureGeneration(): Long = synchronized(lock) { generation }

    fun isCurrent(observedGeneration: Long): Boolean =
        synchronized(lock) { !exhausted && generation == observedGeneration }

    fun markEnded(observedGeneration: Long): Boolean =
        synchronized(lock) {
            if (exhausted || generation != observedGeneration) {
                false
            } else {
                endedGeneration = observedGeneration
                true
            }
        }

    fun replayGeneration(): Long? =
        synchronized(lock) {
            check(!exhausted) { "Playback completion epoch exhausted" }
            val replay = endedGeneration?.takeIf { it == generation } ?: return@synchronized null
            if (generation == Long.MAX_VALUE) {
                endedGeneration = null
                exhausted = true
                error("Playback completion epoch exhausted")
            }
            replay
        }

    fun completeReplay(observedGeneration: Long): Boolean =
        synchronized(lock) {
            if (exhausted || generation != observedGeneration || endedGeneration != observedGeneration) {
                false
            } else {
                endedGeneration = null
                generation += 1
                true
            }
        }

    fun reset() {
        synchronized(lock) {
            endedGeneration = null
            if (exhausted) return
            if (generation == Long.MAX_VALUE) {
                exhausted = true
            } else {
                generation += 1
            }
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
    fun markEndedIfConsumed(
        observedGeneration: Long,
        consumeEnd: () -> Boolean,
    ): Boolean {
        if (!completion.isCurrent(observedGeneration) || !consumeEnd()) return false
        return completion.markEnded(observedGeneration)
    }

    fun runIfCurrent(
        observedGeneration: Long,
        action: () -> Unit,
    ): Boolean {
        if (!completion.isCurrent(observedGeneration)) return false
        action()
        return completion.isCurrent(observedGeneration)
    }

    fun runCommand(action: () -> Unit) = action()

    fun resume(
        seekToStart: () -> Unit,
        play: () -> Unit,
    ) {
        val replayGeneration = completion.replayGeneration()
        if (replayGeneration == null) {
            play()
            return
        }

        // Source command tickets serialize these native operations without a
        // bookkeeping monitor crossing JNI.
        seekToStart()
        play()
        check(completion.completeReplay(replayGeneration)) {
            "Replay generation changed while playback command was active"
        }
    }

    fun reset() {
        resetAndRun {}
    }

    fun resetAndRun(action: () -> Unit) {
        completion.reset()
        action()
    }
}
