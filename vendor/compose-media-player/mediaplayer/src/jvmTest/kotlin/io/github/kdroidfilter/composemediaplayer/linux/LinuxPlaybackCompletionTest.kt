package io.github.kdroidfilter.composemediaplayer.linux

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxPlaybackCompletionTest {
    @Test
    fun serializationOnlyCommandDoesNotInvalidateManagedPublicationOwner() {
        val commands = LinuxSourcePlaybackContext(1L)
        commands.finishOpening()
        val explicitSeek = commands.reserveCommand(requiresCurrentIntent = false)
        commands.reserveCommand(claimsPublication = false)
        var commits = 0

        assertTrue(
            commands.commitIfLatest(explicitSeek) {
                commits += 1
                true
            },
        )

        commands.reserveCommand()
        assertFalse(
            commands.commitIfLatest(explicitSeek) {
                commits += 1
                true
            },
        )
        assertEquals(1, commits)
    }

    @Test
    fun cancelledCommandAwaitingPredecessorDoesNotStrandSuccessors() {
        runBlocking {
            val commands = LinuxSourcePlaybackContext(1L)
            commands.finishOpening()
            val predecessor = commands.reserveCommand()
            val cancelled = commands.reserveCommand()
            val successor = commands.reserveCommand()

            val waiting =
                async(start = CoroutineStart.UNDISPATCHED) {
                    commands.runCommand(cancelled) { error("cancelled command executed") }
                }
            waiting.cancel()
            commands.runCommand(predecessor) { }
            waiting.join()

            withTimeout(1_000) {
                commands.runCommand(successor) { }
            }
        }
    }

    @Test
    fun cancelledCommandCannotReleaseSuccessorBeforeItsPredecessor() {
        runBlocking {
            val successorAwaitingPredecessor = CountDownLatch(1)
            val awaitEntries = AtomicInteger()
            val commands =
                LinuxSourcePlaybackContext(
                    1L,
                    predecessorAwaitRegisteredForTest = {
                        if (awaitEntries.incrementAndGet() == 2) successorAwaitingPredecessor.countDown()
                    },
                )
            commands.finishOpening()
            val predecessor = commands.reserveCommand()
            val cancelled = commands.reserveCommand()
            val successor = commands.reserveCommand()

            val cancelledJob =
                async(start = CoroutineStart.UNDISPATCHED) {
                    commands.runCommand(cancelled) { error("cancelled command executed") }
                }
            cancelledJob.cancel()
            val successorRan = AtomicBoolean(false)
            val successorJob =
                async(start = CoroutineStart.UNDISPATCHED) {
                    commands.runCommand(successor) { successorRan.set(true) }
                }

            assertTrue(successorAwaitingPredecessor.await(2, TimeUnit.SECONDS))
            assertFalse(successorRan.get())
            commands.runCommand(predecessor) {}
            cancelledJob.join()
            successorJob.await()
            assertTrue(successorRan.get())
        }
    }

    @Test
    fun abandonedCommandFallbackPreservesPredecessorOrder() {
        runBlocking {
            val successorAwaitingAbandoned = CountDownLatch(1)
            val commands =
                LinuxSourcePlaybackContext(
                    1L,
                    predecessorAwaitRegisteredForTest = { successorAwaitingAbandoned.countDown() },
                )
            commands.finishOpening()
            val predecessor = commands.reserveCommand()
            val abandoned = commands.reserveCommand()
            val successor = commands.reserveCommand()

            commands.settleIfExecutionNeverStarted(abandoned)
            val successorRan = AtomicBoolean(false)
            val successorJob =
                async(start = CoroutineStart.UNDISPATCHED) {
                    commands.runCommand(successor) { successorRan.set(true) }
                }

            assertTrue(successorAwaitingAbandoned.await(2, TimeUnit.SECONDS))
            assertFalse(abandoned.completion.isCompleted)
            assertFalse(successorRan.get())

            commands.runCommand(predecessor) {}
            successorJob.await()
            assertTrue(abandoned.completion.isCompleted)
            assertTrue(successorRan.get())
        }
    }

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
    fun playbackEpochExhaustionFailsClosedBeforeReplayNativeCalls() {
        val completion = LinuxPlaybackCompletion(initialGeneration = Long.MAX_VALUE)
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val calls = mutableListOf<String>()
        val generation = completion.captureGeneration()

        assertTrue(completion.markEnded(generation))
        assertFailsWith<IllegalStateException> {
            coordinator.resume(
                seekToStart = { calls += "seek" },
                play = { calls += "play" },
            )
        }

        assertEquals(emptyList(), calls)
        assertFalse(completion.isCurrent(generation))
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
        val replayAwaitEntered = CountDownLatch(1)
        val awaitEntries = AtomicInteger()
        val commands =
            LinuxSourcePlaybackContext(
                1L,
                predecessorAwaitRegisteredForTest = {
                    if (awaitEntries.incrementAndGet() == 1) replayAwaitEntered.countDown()
                },
            )
        commands.finishOpening()
        val generation = completion.captureGeneration()
        val consumeEntered = CountDownLatch(1)
        val allowConsume = CountDownLatch(1)
        val endAccepted = AtomicBoolean()
        val replayFromEnd = AtomicBoolean()
        val endTicket = commands.reserveCommand()
        val replayTicket = commands.reserveCommand()
        val executor = Executors.newFixedThreadPool(3)
        try {
            val endFuture =
                executor.submit {
                    runBlocking {
                        commands.runCommand(endTicket) {
                            endAccepted.set(
                                coordinator.markEndedIfConsumed(generation) {
                                    consumeEntered.countDown()
                                    check(allowConsume.await(2, TimeUnit.SECONDS))
                                    true
                                },
                            )
                        }
                    }
                }
            assertTrue(consumeEntered.await(2, TimeUnit.SECONDS))

            val stateReadFuture = executor.submit<Long> { completion.captureGeneration() }
            assertEquals(generation, stateReadFuture.get(2, TimeUnit.SECONDS))

            val replayFuture =
                executor.submit {
                    runBlocking {
                        commands.runCommand(replayTicket) {
                            coordinator.resume(
                                seekToStart = { replayFromEnd.set(true) },
                                play = {},
                            )
                        }
                    }
                }
            assertTrue(replayAwaitEntered.await(2, TimeUnit.SECONDS))
            assertFalse(replayFromEnd.get())

            allowConsume.countDown()
            endFuture.get(2, TimeUnit.SECONDS)
            replayFuture.get(2, TimeUnit.SECONDS)

            assertTrue(endAccepted.get())
            assertTrue(replayFromEnd.get())
        } finally {
            allowConsume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentPlayCannotPassReplaySeek() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val successorAwaitEntered = CountDownLatch(1)
        val awaitEntries = AtomicInteger()
        val serialization =
            LinuxSourcePlaybackContext(
                1L,
                predecessorAwaitRegisteredForTest = {
                    if (awaitEntries.incrementAndGet() == 1) successorAwaitEntered.countDown()
                },
            )
        serialization.finishOpening()
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val seekEntered = CountDownLatch(1)
        val allowSeek = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            completion.markEnded(completion.captureGeneration())
            val replayTicket = serialization.reserveCommand()
            val ordinaryTicket = serialization.reserveCommand()
            val replayFuture =
                executor.submit {
                    runBlocking {
                        serialization.runCommand(replayTicket) {
                            coordinator.resume(
                                seekToStart = {
                                    commands += "seek:0"
                                    seekEntered.countDown()
                                    check(allowSeek.await(2, TimeUnit.SECONDS))
                                },
                                play = { commands += "play:replay" },
                            )
                        }
                    }
                }
            assertTrue(seekEntered.await(2, TimeUnit.SECONDS))

            val ordinaryPlayFuture =
                executor.submit {
                    runBlocking {
                        serialization.runCommand(ordinaryTicket) {
                            coordinator.resume(
                                seekToStart = { commands += "unexpected-seek" },
                                play = { commands += "play:ordinary" },
                            )
                        }
                    }
                }
            assertTrue(successorAwaitEntered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("seek:0"), synchronized(commands) { commands.toList() })

            allowSeek.countDown()
            replayFuture.get(2, TimeUnit.SECONDS)
            ordinaryPlayFuture.get(2, TimeUnit.SECONDS)

            assertEquals(listOf("seek:0", "play:replay", "play:ordinary"), synchronized(commands) { commands.toList() })
        } finally {
            allowSeek.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun explicitResetAndSeekCannotSplitReplayCommands() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val successorAwaitEntered = CountDownLatch(1)
        val awaitEntries = AtomicInteger()
        val serialization =
            LinuxSourcePlaybackContext(
                1L,
                predecessorAwaitRegisteredForTest = {
                    if (awaitEntries.incrementAndGet() == 1) successorAwaitEntered.countDown()
                },
            )
        serialization.finishOpening()
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val replaySeekEntered = CountDownLatch(1)
        val allowReplaySeek = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            completion.markEnded(completion.captureGeneration())
            val replayTicket = serialization.reserveCommand()
            val resetTicket = serialization.reserveCommand()
            val replayFuture =
                executor.submit {
                    runBlocking {
                        serialization.runCommand(replayTicket) {
                            coordinator.resume(
                                seekToStart = {
                                    commands += "seek:0"
                                    replaySeekEntered.countDown()
                                    check(allowReplaySeek.await(2, TimeUnit.SECONDS))
                                },
                                play = { commands += "play:replay" },
                            )
                        }
                    }
                }
            assertTrue(replaySeekEntered.await(2, TimeUnit.SECONDS))

            val explicitSeekFuture =
                executor.submit {
                    runBlocking {
                        serialization.runCommand(resetTicket) {
                            coordinator.resetAndRun { commands += "seek:explicit" }
                        }
                    }
                }
            assertTrue(successorAwaitEntered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("seek:0"), synchronized(commands) { commands.toList() })

            allowReplaySeek.countDown()
            replayFuture.get(2, TimeUnit.SECONDS)
            explicitSeekFuture.get(2, TimeUnit.SECONDS)

            coordinator.resume(
                seekToStart = { commands += "unexpected-seek" },
                play = { commands += "play:ordinary" },
            )
            assertEquals(
                listOf("seek:0", "play:replay", "seek:explicit", "play:ordinary"),
                synchronized(commands) { commands.toList() },
            )
        } finally {
            allowReplaySeek.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun finalizerNativeWorkDoesNotBlockStateReadersAndCompletesBeforeReplay() {
        val completion = LinuxPlaybackCompletion()
        val coordinator = LinuxPlaybackResumeCoordinator(completion)
        val replayAwaitEntered = CountDownLatch(1)
        val awaitEntries = AtomicInteger()
        val commands =
            LinuxSourcePlaybackContext(
                1L,
                predecessorAwaitRegisteredForTest = {
                    if (awaitEntries.incrementAndGet() == 1) replayAwaitEntered.countDown()
                },
            )
        commands.finishOpening()
        val generation = completion.captureGeneration()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val finalizerEntered = CountDownLatch(1)
        val allowFinalizer = CountDownLatch(1)
        val finalizerTicket = commands.reserveCommand()
        val replayTicket = commands.reserveCommand()
        val executor = Executors.newFixedThreadPool(3)
        try {
            completion.markEnded(generation)
            val finalizerFuture =
                executor.submit {
                    runBlocking {
                        commands.runCommand(finalizerTicket) {
                            coordinator.runIfCurrent(generation) {
                                finalizerEntered.countDown()
                                check(allowFinalizer.await(2, TimeUnit.SECONDS))
                                events += "callback"
                            }
                        }
                    }
                }
            assertTrue(finalizerEntered.await(2, TimeUnit.SECONDS))

            val stateReadFuture = executor.submit<Long> { completion.captureGeneration() }
            assertEquals(generation, stateReadFuture.get(2, TimeUnit.SECONDS))

            val replayFuture =
                executor.submit {
                    runBlocking {
                        commands.runCommand(replayTicket) {
                            coordinator.resume(
                                seekToStart = { events += "seek:0" },
                                play = { events += "play" },
                            )
                        }
                    }
                }
            assertTrue(replayAwaitEntered.await(2, TimeUnit.SECONDS))
            assertEquals(emptyList(), synchronized(events) { events.toList() })

            allowFinalizer.countDown()
            finalizerFuture.get(2, TimeUnit.SECONDS)
            replayFuture.get(2, TimeUnit.SECONDS)

            assertEquals(listOf("callback", "seek:0", "play"), synchronized(events) { events.toList() })
        } finally {
            allowFinalizer.countDown()
            executor.shutdownNow()
        }
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
