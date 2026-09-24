package io.github.kdroidfilter.composemediaplayer.linux

/**
 * Non-reusing source identity and atomic publication gate.
 * [publishIfCurrent] is only for short, non-suspending state assignments; JNI,
 * dispatch, destruction, and user callbacks must happen outside its lock.
 */
internal class LinuxPlayerGeneration(
    initialGeneration: Long = 0L,
) {
    private val lock = Any()
    private var current: Long = initialGeneration
    private var closed = false

    init {
        require(initialGeneration >= 0L)
    }

    fun advance(): Long =
        synchronized(lock) {
            if (closed || current == Long.MAX_VALUE) {
                closed = true
                current = 0L
                0L
            } else {
                current += 1L
                current
            }
        }

    fun isCurrent(generation: Long): Boolean =
        synchronized(lock) { !closed && generation != 0L && generation == current }

    fun capture(): Long = synchronized(lock) { if (closed) 0L else current }

    fun close() {
        synchronized(lock) {
            closed = true
            current = 0L
        }
    }

    fun publishIfCurrent(
        generation: Long,
        publication: () -> Unit,
    ): Boolean =
        synchronized(lock) {
            if (closed || generation == 0L || generation != current) {
                false
            } else {
                publication()
                true
            }
        }
}
