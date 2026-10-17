package io.github.kdroidfilter.composemediaplayer.linux

/** Serializes ownership of a generation-tagged asynchronous job slot. */
internal class LinuxGenerationJobSlot<T : Any>(
    private val cancelJob: (T) -> Unit,
) {
    internal data class Ticket(
        val sourceGeneration: Long,
        val serial: Long,
    )

    internal data class Reservation<T>(
        val ticket: Ticket,
        internal val displaced: T?,
    )

    private data class Owner<T>(
        val ticket: Ticket,
        val job: T?,
    )

    private val lock = Any()
    private var nextSerial = 0L
    private var owner: Owner<T>? = null

    fun reserveDeferredCancellation(sourceGeneration: Long): Reservation<T> {
        require(sourceGeneration != 0L)
        val previous: T?
        val ticket: Ticket
        synchronized(lock) {
            check(nextSerial != Long.MAX_VALUE) { "Job-slot ticket space exhausted" }
            ticket = Ticket(sourceGeneration, ++nextSerial)
            previous = owner?.job
            owner = Owner(ticket, null)
        }
        return Reservation(ticket, previous)
    }

    fun cancelDisplaced(reservation: Reservation<T>) {
        reservation.displaced?.let(cancelJob)
    }

    fun reserve(sourceGeneration: Long): Ticket =
        reserveDeferredCancellation(sourceGeneration).also(::cancelDisplaced).ticket

    fun install(
        ticket: Ticket,
        job: T,
    ): Boolean {
        val installed =
            synchronized(lock) {
                if (owner?.ticket == ticket) {
                    owner = Owner(ticket, job)
                    true
                } else {
                    false
                }
            }
        if (!installed) cancelJob(job)
        return installed
    }

    fun capture(sourceGeneration: Long): Ticket? =
        synchronized(lock) {
            owner?.takeIf { it.ticket.sourceGeneration == sourceGeneration }?.ticket
        }

    fun cancel(ticket: Ticket?): Boolean {
        if (ticket == null) return false
        val cancelled =
            synchronized(lock) {
                owner?.takeIf { it.ticket == ticket }?.also { owner = null }
            } ?: return false
        cancelled.job?.let(cancelJob)
        return true
    }

    fun cancelCurrent(): Boolean {
        val cancelled = synchronized(lock) { owner.also { owner = null }?.job } ?: return false
        cancelJob(cancelled)
        return true
    }

    fun clear(
        ticket: Ticket,
        job: T,
    ): Boolean =
        synchronized(lock) {
            val current = owner
            if (current?.ticket == ticket && current.job === job) {
                owner = null
                true
            } else {
                false
            }
        }

    fun isOwner(
        ticket: Ticket,
        job: T,
    ): Boolean = synchronized(lock) { owner?.let { it.ticket == ticket && it.job === job } == true }

    internal fun ownerTicketForTest(): Ticket? = synchronized(lock) { owner?.ticket }
}
