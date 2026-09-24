package io.github.kdroidfilter.composemediaplayer.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class LinuxFrameTargetOwnershipTest {
    private class Target(val id: Int, val closed: MutableList<Int>) {
        fun close() {
            closed += id
        }
    }

    @Test
    fun secondAllocationFailureClosesFirstTarget() {
        val closed = mutableListOf<Int>()
        var allocation = 0

        assertFailsWith<IllegalStateException> {
            allocateOwnedFrameTargets(
                create = {
                    allocation += 1
                    if (allocation == 2) error("second allocation failed")
                    Target(allocation, closed)
                },
                close = Target::close,
                peek = { it.id },
                address = Int::toLong,
                size = { 4L },
                wrap = { _, size -> size },
            )
        }

        assertEquals(listOf(1), closed)
    }

    @Test
    fun peekOrSizingFailureClosesBothTargets() {
        listOf("peek", "size").forEach { failurePoint ->
            val closed = mutableListOf<Int>()
            var allocation = 0

            assertFailsWith<IllegalStateException>(failurePoint) {
                allocateOwnedFrameTargets(
                    create = { Target(++allocation, closed) },
                    close = Target::close,
                    peek = { target ->
                        if (failurePoint == "peek" && target.id == 2) error("peek failed")
                        target.id
                    },
                    address = Int::toLong,
                    size = { pixmap ->
                        if (failurePoint == "size" && pixmap == 2) error("sizing failed")
                        4L
                    },
                    wrap = { _, size -> size },
                )
            }

            assertEquals(listOf(2, 1), closed, failurePoint)
        }
    }

    @Test
    fun wrapFailureClosesBothTargets() {
        val closed = mutableListOf<Int>()
        var allocation = 0

        assertFailsWith<IllegalStateException> {
            allocateOwnedFrameTargets(
                create = { Target(++allocation, closed) },
                close = Target::close,
                peek = { it.id },
                address = Int::toLong,
                size = { 4L },
                wrap = { address, size ->
                    if (address == 2L) error("wrap failed")
                    size
                },
            )
        }

        assertEquals(listOf(2, 1), closed)
    }

    @Test
    fun successfulAllocationTransfersOwnershipWithoutClosingTargets() {
        val closed = mutableListOf<Int>()
        var allocation = 0

        val targets =
            allocateOwnedFrameTargets(
                create = { Target(++allocation, closed) },
                close = Target::close,
                peek = { it.id },
                address = Int::toLong,
                size = { 4L },
                wrap = { address, _ -> address },
            )

        assertNotNull(targets)
        assertEquals(1, targets.firstTarget.id)
        assertEquals(2, targets.secondTarget.id)
        assertEquals(1L, targets.firstBuffer)
        assertEquals(2L, targets.secondBuffer)
        assertEquals(emptyList(), closed)
    }

    @Test
    fun failureClosingEitherOldTargetClosesBothOldAndNewTargets() {
        listOf(1, 2).forEach { failingOld ->
            val closeAttempts = mutableListOf<Int>()
            val replacement =
                LinuxFrameTargets(
                    firstTarget = 3,
                    secondTarget = 4,
                    firstBuffer = 30,
                    secondBuffer = 40,
                )

            val failure =
                assertFailsWith<IllegalStateException> {
                    transferFrameTargetOwnership(
                        oldFirst = 1,
                        oldSecond = 2,
                        replacement = replacement,
                        close = { id ->
                            closeAttempts += id
                            if (id == failingOld) throw IllegalStateException("close $id")
                        },
                    )
                }

            assertEquals(listOf(1, 2, 3, 4), closeAttempts)
            assertEquals("close $failingOld", failure.message)
        }
    }

    @Test
    fun replacementFailureClosesBothOldAndNewTargetsPreservingPrimaryFailure() {
        val closeAttempts = mutableListOf<Int>()
        val replacement =
            LinuxFrameTargets(
                firstTarget = 3,
                secondTarget = 4,
                firstBuffer = 30,
                secondBuffer = 40,
            )

        val failure =
            assertFailsWith<IllegalStateException> {
                transferFrameTargetOwnership(
                    oldFirst = 1,
                    oldSecond = 2,
                    replacement = replacement,
                    close = { id ->
                        closeAttempts += id
                        throw IllegalStateException("close $id")
                    },
                )
            }

        assertEquals(listOf(1, 2, 3, 4), closeAttempts)
        assertEquals("close 1", failure.message)
        assertEquals(listOf("close 2", "close 3", "close 4"), failure.suppressed.map { it.message })
    }
}
