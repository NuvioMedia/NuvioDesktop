package io.github.kdroidfilter.composemediaplayer.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxGenerationJobSlotTest {
    @Test
    fun `stale reservation cannot replace a newer job`() {
        val cancelled = mutableListOf<String>()
        val slot = LinuxGenerationJobSlot<String>(cancelled::add)

        val stale = slot.reserve(sourceGeneration = 4L)
        val current = slot.reserve(sourceGeneration = 5L)

        assertFalse(slot.install(stale, "stale"))
        assertTrue(slot.install(current, "current"))
        assertEquals(listOf("stale"), cancelled)
        assertTrue(slot.isOwner(current, "current"))
    }

    @Test
    fun `delayed cancellation cannot cancel a newer same-generation job`() {
        val cancelled = mutableListOf<String>()
        val slot = LinuxGenerationJobSlot<String>(cancelled::add)

        val first = slot.reserve(sourceGeneration = 9L)
        assertTrue(slot.install(first, "first"))
        val observedByPause = slot.capture(sourceGeneration = 9L)

        val replacement = slot.reserve(sourceGeneration = 9L)
        assertTrue(slot.install(replacement, "replacement"))

        assertFalse(slot.cancel(observedByPause))
        assertTrue(slot.isOwner(replacement, "replacement"))
        assertEquals(listOf("first"), cancelled)
    }

    @Test
    fun `stale completion cannot clear replacement`() {
        val cancelled = mutableListOf<String>()
        val slot = LinuxGenerationJobSlot<String>(cancelled::add)
        val first = slot.reserve(11L)
        assertTrue(slot.install(first, "first"))
        val second = slot.reserve(12L)
        assertTrue(slot.install(second, "second"))

        assertFalse(slot.clear(first, "first"))
        assertTrue(slot.isOwner(second, "second"))
        assertTrue(slot.clear(second, "second"))
        assertFalse(slot.cancelCurrent())
        assertEquals(listOf("first"), cancelled)
    }

    @Test
    fun `deferred reservation never cancels until outside caller releases displaced job`() {
        lateinit var slot: LinuxGenerationJobSlot<String>
        var reentrant: LinuxGenerationJobSlot.Ticket? = null
        val cancelled = mutableListOf<String>()
        slot =
            LinuxGenerationJobSlot { job ->
                cancelled += job
                reentrant = slot.reserve(22L)
            }
        val first = slot.reserve(20L)
        assertTrue(slot.install(first, "first"))

        val replacement = slot.reserveDeferredCancellation(21L)

        assertEquals(emptyList(), cancelled)
        assertNull(reentrant)
        assertEquals(replacement.ticket, slot.capture(21L))

        slot.cancelDisplaced(replacement)

        assertEquals(listOf("first"), cancelled)
        assertEquals(reentrant, slot.capture(22L))
    }
}
