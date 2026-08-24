package io.github.kdroidfilter.composemediaplayer.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections
import kotlin.concurrent.thread

class LinuxPlaybackCompletionTest {
    @Test
    fun ordinaryPlayDoesNotSeek() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val commands = mutableListOf<String>()

        coordinator.resume(
            seekToStart = { commands += "seek:0" },
            play = { commands += "play" },
        )

        assertEquals(listOf("play"), commands)
    }

    @Test
    fun playAfterEndSeeksToStartBeforePlaying() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val commands = mutableListOf<String>()

        completion.markEnded(completion.captureGeneration())
        coordinator.resume(
            seekToStart = { commands += "seek:0" },
            play = { commands += "play" },
        )

        assertEquals(listOf("seek:0", "play"), commands)
    }

    @Test
    fun explicitResetPreventsStaleEndFromRewindingLaterPlay() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val commands = mutableListOf<String>()

        completion.markEnded(completion.captureGeneration())
        completion.reset()
        coordinator.resume(
            seekToStart = { commands += "seek:0" },
            play = { commands += "play" },
        )

        assertEquals(listOf("play"), commands)
    }

    @Test
    fun endMarkerIsConsumedByOnlyOnePlay() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val commands = mutableListOf<String>()

        completion.markEnded(completion.captureGeneration())
        repeat(2) {
            coordinator.resume(
                seekToStart = { commands += "seek:0" },
                play = { commands += "play" },
            )
        }

        assertEquals(listOf("seek:0", "play", "play"), commands)
    }

    @Test
    fun staleEndFromPreviousGenerationCannotRewindCurrentPlayback() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val staleGeneration = completion.captureGeneration()
        val commands = mutableListOf<String>()

        completion.reset()
        val accepted = completion.markEnded(staleGeneration)
        coordinator.resume(
            seekToStart = { commands += "seek:0" },
            play = { commands += "play" },
        )

        assertFalse(accepted)
        assertEquals(listOf("play"), commands)
    }

    @Test
    fun staleGenerationCannotConsumeNativeEndSignal() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val staleGeneration = completion.captureGeneration()
        var nativeConsumeCalls = 0

        completion.reset()
        val ended =
            coordinator.markEndedIfConsumed(staleGeneration) {
                nativeConsumeCalls += 1
                true
            }

        assertEquals(false, ended)
        assertEquals(0, nativeConsumeCalls)
    }

    @Test
    fun replaySupersedesInFlightEndFinalizer() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val endedGeneration = completion.captureGeneration()
        var finalizerCalls = 0

        completion.markEnded(endedGeneration)
        coordinator.resume(seekToStart = {}, play = {})
        val accepted =
            coordinator.runIfCurrent(endedGeneration) {
                finalizerCalls += 1
            }

        assertEquals(false, accepted)
        assertEquals(0, finalizerCalls)
    }

    @Test
    fun nativeEndConsumptionAndMarkerPublicationAreAtomicWithoutBlockingStateReaders() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val generation = completion.captureGeneration()
        val consumeEntered = CountDownLatch(1)
        val allowConsume = CountDownLatch(1)
        val stateReadReturned = CountDownLatch(1)
        val replayReturned = CountDownLatch(1)
        var endAccepted = false
        var replayFromEnd = false

        val endThread = thread(isDaemon = true) {
            endAccepted = coordinator.markEndedIfConsumed(generation) {
                consumeEntered.countDown()
                allowConsume.await()
                true
            }
        }
        assertTrue(consumeEntered.await(2, TimeUnit.SECONDS))

        val stateReader = thread(isDaemon = true) {
            completion.captureGeneration()
            stateReadReturned.countDown()
        }
        assertTrue(stateReadReturned.await(2, TimeUnit.SECONDS))

        val replayThread = thread(isDaemon = true) {
            coordinator.resume(
                seekToStart = { replayFromEnd = true },
                play = {},
            )
            replayReturned.countDown()
        }
        assertFalse(replayReturned.await(100, TimeUnit.MILLISECONDS))

        allowConsume.countDown()
        endThread.join(2_000)
        stateReader.join(2_000)
        replayThread.join(2_000)

        assertFalse(endThread.isAlive)
        assertFalse(stateReader.isAlive)
        assertFalse(replayThread.isAlive)
        assertTrue(endAccepted)
        assertTrue(replayFromEnd)
    }

    @Test
    fun concurrentPlayCannotPassReplaySeek() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val seekEntered = CountDownLatch(1)
        val allowSeek = CountDownLatch(1)
        val secondPlayReturned = CountDownLatch(1)

        completion.markEnded(completion.captureGeneration())
        val replayThread = thread(isDaemon = true) {
            coordinator.resume(
                seekToStart = {
                    commands += "seek:0"
                    seekEntered.countDown()
                    allowSeek.await()
                },
                play = { commands += "play:replay" },
            )
        }
        assertTrue(seekEntered.await(2, TimeUnit.SECONDS))

        val ordinaryPlayThread = thread(isDaemon = true) {
            coordinator.resume(
                seekToStart = { commands += "unexpected-seek" },
                play = { commands += "play:ordinary" },
            )
            secondPlayReturned.countDown()
        }
        assertFalse(secondPlayReturned.await(100, TimeUnit.MILLISECONDS))
        assertEquals(listOf("seek:0"), commands.toList())

        allowSeek.countDown()
        replayThread.join(2_000)
        ordinaryPlayThread.join(2_000)

        assertFalse(replayThread.isAlive)
        assertFalse(ordinaryPlayThread.isAlive)
        assertEquals(listOf("seek:0", "play:replay", "play:ordinary"), commands.toList())
    }

    @Test
    fun explicitResetAndSeekCannotSplitReplayCommands() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val replaySeekEntered = CountDownLatch(1)
        val allowReplaySeek = CountDownLatch(1)
        val explicitSeekReturned = CountDownLatch(1)

        completion.markEnded(completion.captureGeneration())
        val replayThread = thread(isDaemon = true) {
            coordinator.resume(
                seekToStart = {
                    commands += "seek:0"
                    replaySeekEntered.countDown()
                    allowReplaySeek.await()
                },
                play = { commands += "play:replay" },
            )
        }
        assertTrue(replaySeekEntered.await(2, TimeUnit.SECONDS))

        val explicitSeekThread = thread(isDaemon = true) {
            coordinator.resetAndRun { commands += "seek:explicit" }
            explicitSeekReturned.countDown()
        }
        assertFalse(explicitSeekReturned.await(100, TimeUnit.MILLISECONDS))
        assertEquals(listOf("seek:0"), commands.toList())

        allowReplaySeek.countDown()
        replayThread.join(2_000)
        explicitSeekThread.join(2_000)

        assertFalse(replayThread.isAlive)
        assertFalse(explicitSeekThread.isAlive)
        coordinator.resume(
            seekToStart = { commands += "unexpected-seek" },
            play = { commands += "play:ordinary" },
        )
        assertEquals(
            listOf("seek:0", "play:replay", "seek:explicit", "play:ordinary"),
            commands.toList(),
        )
    }

    @Test
    fun finalizerNativeWorkDoesNotBlockStateReadersAndCompletesBeforeReplay() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val generation = completion.captureGeneration()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val finalizerEntered = CountDownLatch(1)
        val allowFinalizer = CountDownLatch(1)
        val stateReadReturned = CountDownLatch(1)
        val replayReturned = CountDownLatch(1)

        completion.markEnded(generation)
        val finalizerThread = thread(isDaemon = true) {
            coordinator.runIfCurrent(generation) {
                finalizerEntered.countDown()
                allowFinalizer.await()
                events += "callback"
            }
        }
        assertTrue(finalizerEntered.await(2, TimeUnit.SECONDS))

        val stateReader = thread(isDaemon = true) {
            completion.captureGeneration()
            stateReadReturned.countDown()
        }
        assertTrue(stateReadReturned.await(2, TimeUnit.SECONDS))

        val replayThread = thread(isDaemon = true) {
            coordinator.resume(
                seekToStart = { events += "seek:0" },
                play = { events += "play" },
            )
            replayReturned.countDown()
        }
        assertFalse(replayReturned.await(100, TimeUnit.MILLISECONDS))

        allowFinalizer.countDown()
        finalizerThread.join(2_000)
        stateReader.join(2_000)
        replayThread.join(2_000)

        assertFalse(finalizerThread.isAlive)
        assertFalse(stateReader.isAlive)
        assertFalse(replayThread.isAlive)
        assertEquals(listOf("callback", "seek:0", "play"), events.toList())
    }

    @Test
    fun replayPlayFailurePreservesEndMarkerForRetry() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val commands = mutableListOf<String>()

        completion.markEnded(completion.captureGeneration())
        assertFailsWith<IllegalStateException> {
            coordinator.resume(
                seekToStart = { commands += "seek:first" },
                play = { throw IllegalStateException("play failed") },
            )
        }
        coordinator.resume(
            seekToStart = { commands += "seek:retry" },
            play = { commands += "play" },
        )

        assertEquals(listOf("seek:first", "seek:retry", "play"), commands)
    }

    @Test
    fun replaySeekFailurePreservesEndMarkerForRetry() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val commands = mutableListOf<String>()

        completion.markEnded(completion.captureGeneration())
        assertFailsWith<IllegalStateException> {
            coordinator.resume(
                seekToStart = { throw IllegalStateException("seek failed") },
                play = { commands += "unexpected-play" },
            )
        }
        coordinator.resume(
            seekToStart = { commands += "seek:0" },
            play = { commands += "play" },
        )

        assertEquals(listOf("seek:0", "play"), commands)
    }

    @Test
    fun staleGenerationCannotRunCompletionSideEffects() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val staleGeneration = completion.captureGeneration()
        var sideEffectCalls = 0

        completion.reset()
        val accepted =
            coordinator.runIfCurrent(staleGeneration) {
                sideEffectCalls += 1
            }

        assertEquals(false, accepted)
        assertEquals(0, sideEffectCalls)
    }
}
