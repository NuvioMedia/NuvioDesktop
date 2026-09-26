package io.github.kdroidfilter.composemediaplayer.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxPlayerGenerationTest {
    @Test
    fun `stale publication is rejected after advance`() {
        val generations = LinuxPlayerGeneration()
        val first = generations.advance()
        var publication = "initial"

        val second = generations.advance()

        assertFalse(generations.publishIfCurrent(first) { publication = "stale" })
        assertTrue(generations.publishIfCurrent(second) { publication = "current" })
        assertEquals("current", publication)
    }

    @Test
    fun `exhaustion fails closed without reusing an identity`() {
        val generations = LinuxPlayerGeneration(initialGeneration = Long.MAX_VALUE - 1L)

        assertEquals(Long.MAX_VALUE, generations.advance())
        assertEquals(0L, generations.advance())
        assertEquals(0L, generations.capture())
        assertFalse(generations.publishIfCurrent(Long.MAX_VALUE) {})
        assertEquals(0L, generations.advance())
    }

    @Test
    fun `close permanently rejects later generations`() {
        val generations = LinuxPlayerGeneration()
        val active = generations.advance()

        generations.close()

        assertFalse(generations.publishIfCurrent(active) {})
        assertEquals(0L, generations.advance())
        assertEquals(0L, generations.capture())
    }
}
