package io.github.kdroidfilter.composemediaplayer.linux
import io.github.kdroidfilter.composemediaplayer.DefaultVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinuxVideoPlayerStateLifecycleTest {
    private var cleanupHarness: TestCleanupHarness? = null

    private fun lifecycleTest(block: suspend () -> Unit) =
        runBlocking {
            check(cleanupHarness == null)
            val harness = TestCleanupHarness()
            cleanupHarness = harness
            try {
                harness.run(block)
            } finally {
                cleanupHarness = null
            }
        }

    private fun deferDisposal(state: LinuxVideoPlayerState) {
        checkNotNull(cleanupHarness).defer {
            state.disposeAsync().get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `source ready callback blocks replacement generation transition`() =
        lifecycleTest {
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val transitionWaiting = CountDownLatch(1)
            val lifecycle =
                LinuxPlayerLifecycle(transitionWaitingForCallbacksForTest = { transitionWaiting.countDown() })
            val state =
                LinuxVideoPlayerState(
                    FakeBridge(),
                    lifecycle,
                    sourceReadyObserver = { _, _ ->
                        callbackEntered.countDown()
                        check(releaseCallback.await(5, TimeUnit.SECONDS))
                    },
                    frameRenderingEnabled = false,
                )
            val executor = Executors.newSingleThreadExecutor()
            try {
                state.openUri("https://example.invalid/source-ready-a.mp4", InitialPlayerState.PAUSE)
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))

                val replacement =
                    executor.submit {
                        state.openUri("https://example.invalid/source-ready-b.mp4", InitialPlayerState.PAUSE)
                    }
                assertTrue(
                    transitionWaiting.await(5, TimeUnit.SECONDS),
                    "replacement did not wait for the active source-ready callback",
                )

                releaseCallback.countDown()
                replacement.get(5, TimeUnit.SECONDS)
            } finally {
                releaseCallback.countDown()
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `open job cancellation occurs outside lifecycle and request locks`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val callbackExecutor = Executors.newSingleThreadExecutor()
            val stateRef = AtomicReference<LinuxVideoPlayerState>()
            val firstCancellation = AtomicBoolean(true)
            val cancellationFailure = AtomicReference<Throwable?>()
            val sourceReady = LinkedBlockingQueue<Long>()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { generation, _ -> sourceReady.put(generation) },
                    frameRenderingEnabled = false,
                    beforeOpenJobCancelForTest = {
                        if (firstCancellation.compareAndSet(true, false)) {
                            try {
                                callbackExecutor
                                    .submit {
                                        stateRef.get().openUri(
                                            "https://example.invalid/reentrant-c.mp4",
                                            InitialPlayerState.PAUSE,
                                        )
                                    }.get(2, TimeUnit.SECONDS)
                            } catch (failure: Throwable) {
                                cancellationFailure.set(failure)
                            }
                        }
                    },
                )
            stateRef.set(state)
            try {
                state.openUri("https://example.invalid/a.mp4", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.poll(5, TimeUnit.SECONDS) != null)
                state.openUri("https://example.invalid/b.mp4", InitialPlayerState.PAUSE)
                assertNull(cancellationFailure.get())
                assertTrue(
                    sourceReady.poll(5, TimeUnit.SECONDS) != null,
                    "reentrant source never became ready; calls=${bridge.calls()}",
                )
                assertTrue(bridge.calls().any { it.name == "open" && it.handle == 82L })
            } finally {
                deferDisposal(state)
                callbackExecutor.shutdownNow()
            }
        }

    @Test
    fun `stale open cleanup cannot cancel replacement generation workers`() =
        lifecycleTest {
            val firstCleanupEntered = CountDownLatch(1)
            val releaseFirstCleanup = CountDownLatch(1)
            val blockFirstCleanup = AtomicBoolean(true)
            val staleGeneration = AtomicLong(0L)
            val staleOpenCompleted = CountDownLatch(1)
            val sourceReady = LinkedBlockingQueue<Long>()
            val state =
                LinuxVideoPlayerState(
                    FakeBridge(),
                    sourceReadyObserver = { generation, _ -> sourceReady.put(generation) },
                    frameRenderingEnabled = false,
                    beforeOpenCleanupForTest = {
                        if (blockFirstCleanup.compareAndSet(true, false)) {
                            firstCleanupEntered.countDown()
                            check(releaseFirstCleanup.await(5, TimeUnit.SECONDS))
                        }
                    },
                    sourceOpenCompletedForTest = { generation, _ ->
                        if (generation == staleGeneration.get()) staleOpenCompleted.countDown()
                    },
                )
            try {
                state.openUri("https://example.invalid/stale-open.mp4", InitialPlayerState.PAUSE)
                assertTrue(firstCleanupEntered.await(5, TimeUnit.SECONDS))
                staleGeneration.set(state.captureSourceGenerationForTest())

                state.openUri("https://example.invalid/replacement.mp4", InitialPlayerState.PAUSE)
                val replacementGeneration = assertNotNull(sourceReady.poll(5, TimeUnit.SECONDS))
                assertTrue(replacementGeneration > staleGeneration.get())
                val replacementFrameTicket = assertNotNull(state.frameJobTicketForTest())
                val replacementBufferingTicket = assertNotNull(state.bufferingJobTicketForTest())

                releaseFirstCleanup.countDown()
                assertTrue(staleOpenCompleted.await(5, TimeUnit.SECONDS))
                assertEquals(replacementFrameTicket, state.frameJobTicketForTest())
                assertEquals(replacementBufferingTicket, state.bufferingJobTicketForTest())
            } finally {
                releaseFirstCleanup.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `blocked old open does not prevent replacement generation advance`() =
        lifecycleTest {
            val bridge = FakeBridge(blockOpenHandle = 82L)
            val completedSources = LinkedBlockingQueue<Long>()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceOpenCompletedForTest = { generation, failure ->
                        if (failure == null) completedSources.put(generation)
                    },
                    frameRenderingEnabled = false,
                )
            val executor = Executors.newSingleThreadExecutor()
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/blocked.bmp", InitialPlayerState.PAUSE)
                assertTrue(bridge.blockedOpenEntered.await(5, TimeUnit.SECONDS))
                val oldGeneration = state.captureSourceGenerationForTest()

                executor
                    .submit {
                        state.openUri("https://example.invalid/replacement.bmp", InitialPlayerState.PAUSE)
                    }.get(2, TimeUnit.SECONDS)
                val replacementGeneration = state.captureSourceGenerationForTest()
                assertTrue(replacementGeneration > oldGeneration)
                assertNull(state.withPlayerForTest(oldGeneration) { it })
                assertNull(state.withPlayerForTest(replacementGeneration) { it })

                bridge.releaseBlockedOpen.countDown()
                val completedGeneration = assertNotNull(completedSources.poll(5, TimeUnit.SECONDS))
                assertEquals(replacementGeneration, completedGeneration)
                assertEquals(123L, state.withPlayerForTest(replacementGeneration) { it })
                assertEquals(1, bridge.disposed().count { it == 82L })
            } finally {
                bridge.releaseBlockedOpen.countDown()
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `native play callback can request pause on the same thread without lock inversion`() =
        lifecycleTest {
            val stateRef = AtomicReference<LinuxVideoPlayerState>()
            val bridge = FakeBridge(onPlay = { stateRef.get().pause() })
            val playCompleted = CountDownLatch(1)
            val pauseCompleted = CountDownLatch(1)
            val sourceReady = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    frameRenderingEnabled = false,
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "play") playCompleted.countDown()
                        if (operation == "pause") pauseCompleted.countDown()
                    },
                )
            stateRef.set(state)
            try {
                state.openUri("https://example.invalid/a.mp4", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                state.play()
                assertTrue(playCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(pauseCompleted.await(5, TimeUnit.SECONDS))
                assertFalse(state.isPlaying)
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `pause submitted before play worker installation leaves no paused workers`() =
        lifecycleTest {
            val sourceReady = CountDownLatch(1)
            val playCompleted = CountDownLatch(1)
            val initialPauseCompleted = CountDownLatch(1)
            val racedPauseCompleted = CountDownLatch(1)
            val pauseCompletions = AtomicInteger()
            val injected = AtomicBoolean()
            lateinit var state: LinuxVideoPlayerState
            state =
                LinuxVideoPlayerState(
                    FakeBridge(),
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    frameRenderingEnabled = false,
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "play") playCompleted.countDown()
                        if (operation == "pause") {
                            if (pauseCompletions.incrementAndGet() == 1) {
                                initialPauseCompleted.countDown()
                            } else {
                                racedPauseCompleted.countDown()
                            }
                        }
                    },
                    beforePlayWorkerInstallForTest = {
                        if (injected.compareAndSet(false, true)) state.pause()
                    },
                )
            try {
                state.openUri("https://example.invalid/play-worker-race.mp4", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                state.pause()
                assertTrue(initialPauseCompleted.await(5, TimeUnit.SECONDS))
                assertNull(state.frameJobTicketForTest())
                assertNull(state.bufferingJobTicketForTest())

                state.play()

                assertTrue(playCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(racedPauseCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(injected.get())
                assertFalse(state.isPlaying)
                assertNull(state.frameJobTicketForTest())
                assertNull(state.bufferingJobTicketForTest())
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `pause submitted before seek worker replacement leaves no paused workers`() =
        lifecycleTest {
            val sourceReady = CountDownLatch(1)
            val initialFrameInstalled = CountDownLatch(1)
            val initialBufferingInstalled = CountDownLatch(1)
            val seekCompleted = CountDownLatch(1)
            val pauseCompleted = CountDownLatch(1)
            val injected = AtomicBoolean()
            lateinit var state: LinuxVideoPlayerState
            state =
                LinuxVideoPlayerState(
                    FakeBridge(),
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    frameRenderingEnabled = false,
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "seek") seekCompleted.countDown()
                        if (operation == "pause") pauseCompleted.countDown()
                    },
                    jobTransitionObserverForTest = { kind, event, _ ->
                        if (kind == "frame" && event == "installed") initialFrameInstalled.countDown()
                        if (kind == "buffering" && event == "installed") initialBufferingInstalled.countDown()
                    },
                    beforeSeekWorkerInstallForTest = {
                        if (injected.compareAndSet(false, true)) state.pause()
                    },
                )
            try {
                state.openUri("https://example.invalid/seek-worker-race.mp4", InitialPlayerState.PLAY)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertTrue(initialFrameInstalled.await(5, TimeUnit.SECONDS))
                assertTrue(initialBufferingInstalled.await(5, TimeUnit.SECONDS))
                assertNotNull(state.frameJobTicketForTest())
                assertNotNull(state.bufferingJobTicketForTest())

                state.seekTo(500f)

                assertTrue(seekCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(pauseCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(injected.get())
                assertFalse(state.isPlaying)
                assertNull(state.frameJobTicketForTest())
                assertNull(state.bufferingJobTicketForTest())
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `seek requested while native open is blocked waits for opening completion`() =
        lifecycleTest {
            val bridge = FakeBridge(blockOpenHandle = 82L, trackDurationBeforeOpenComplete = true)
            val openCompleted = CountDownLatch(1)
            val seekCompleted = CountDownLatch(1)
            val seekWaitingForOpening = CountDownLatch(1)
            val seekReservations = AtomicInteger()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceOpenCompletedForTest = { _, _ -> openCompleted.countDown() },
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "seek") seekCompleted.countDown()
                    },
                    commandAwaitingPredecessorForTest = { seekWaitingForOpening.countDown() },
                    commandReservedBeforeLaunchForTest = { operation, _ ->
                        if (operation == "seek") seekReservations.incrementAndGet()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/blocked-seek.mp4", InitialPlayerState.PAUSE)
                assertTrue(bridge.blockedOpenEntered.await(5, TimeUnit.SECONDS))
                assertTrue(state.sourceOpeningForTest())

                state.seekTo(500f)

                assertEquals(1, seekReservations.get())
                assertTrue(seekWaitingForOpening.await(5, TimeUnit.SECONDS))
                assertTrue(bridge.calls().none { it.name == "duration" || it.name == "seek" })

                bridge.releaseBlockedOpen.countDown()
                assertTrue(openCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(seekCompleted.await(5, TimeUnit.SECONDS))
                assertFalse(bridge.durationBeforeOpenComplete.get())
                assertTrue(bridge.calls().any { it.name == "seek" })
            } finally {
                bridge.releaseBlockedOpen.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `stale buffering check cannot publish into replacement source`() =
        lifecycleTest {
            val readySources = LinkedBlockingQueue<Long>()
            val bufferingPublicationEntered = CountDownLatch(1)
            val releaseBufferingPublication = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    FakeBridge(),
                    sourceReadyObserver = { generation, _ -> readySources.put(generation) },
                    beforeBufferingPublicationForTest = {
                        bufferingPublicationEntered.countDown()
                        check(releaseBufferingPublication.await(5, TimeUnit.SECONDS))
                    },
                    frameRenderingEnabled = false,
                )
            val executor = Executors.newSingleThreadExecutor()
            try {
                state.openUri("https://example.invalid/buffering-stale.bmp", InitialPlayerState.PAUSE)
                assertTrue(readySources.poll(5, TimeUnit.SECONDS) != null)
                val staleGeneration = state.captureSourceGenerationForTest()
                val staleCheck =
                    executor.submit<Unit> {
                        runBlocking { state.runBufferingPublicationForTest(staleGeneration) }
                    }
                assertTrue(bufferingPublicationEntered.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/buffering-replacement.bmp", InitialPlayerState.PAUSE)
                assertTrue(readySources.poll(5, TimeUnit.SECONDS) != null)
                releaseBufferingPublication.countDown()
                staleCheck.get(5, TimeUnit.SECONDS)
                assertFalse(state.isLoading)
            } finally {
                releaseBufferingPublication.countDown()
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `EOS does not publish pause or callback without matching player lease`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val ended = AtomicInteger()
            val sourceReady = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    frameRenderingEnabled = false,
                )
            try {
                state.openUri("https://example.invalid/a.mp4", InitialPlayerState.PLAY)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                state.onPlaybackEnded = { ended.incrementAndGet() }
                val generation = state.captureSourceGenerationForTest()
                state.retirePlayerForTest()
                bridge.signalEndForTest()
                state.updatePositionForTest(generation)
                assertTrue(state.isPlaying)
                assertEquals(0, ended.get())
                assertTrue(bridge.calls().none { it.name == "pause" })
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `stop does not reset state without matching player lease`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val stopCompleted = CountDownLatch(1)
            val sourceReady = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    frameRenderingEnabled = false,
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "stop") stopCompleted.countDown()
                    },
                )
            try {
                state.userDragging = true
                state.openUri("https://example.invalid/a.mp4", InitialPlayerState.PLAY)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                state.retirePlayerForTest()
                state.sliderPos = 500f
                state.stop()
                assertTrue(stopCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(state.isPlaying)
                assertEquals(500f, state.sliderPos)
                assertTrue(bridge.calls().none { it.name == "seek" })
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `debounced stale frame is rejected at final assignment and open clears synchronously`() =
        lifecycleTest {
            val deliveries = Collections.synchronizedList(mutableListOf<Pair<Long, Boolean>>())
            val collectorReady = CountDownLatch(1)
            val staleRejected = CountDownLatch(1)
            val collectorProbeGeneration = Long.MIN_VALUE
            val oldGeneration = AtomicLong(0L)
            val bridge = FakeBridge()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    frameDeliveryObserver = { generation, published ->
                        deliveries += generation to published
                        if (generation == collectorProbeGeneration) collectorReady.countDown()
                        if (generation == oldGeneration.get() && !published) staleRejected.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.enqueueFrameForTest(collectorProbeGeneration, null)
                assertTrue(collectorReady.await(5, TimeUnit.SECONDS))
                oldGeneration.set(state.captureSourceGenerationForTest())
                state.openUri("/definitely/missing/stale-frame.bmp", InitialPlayerState.PAUSE)
                assertTrue(state.queuedFrameGenerationForTest() != oldGeneration.get())
                assertNull(state.currentFrameState.value)
                state.enqueueFrameForTest(oldGeneration.get(), null)
                assertTrue(staleRejected.await(5, TimeUnit.SECONDS))
                assertTrue(synchronized(deliveries) { deliveries.any { it == oldGeneration.get() to false } })
                assertNull(state.currentFrameState.value)
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `published frame remains independently readable after producer disposal`() =
        lifecycleTest {
            val framePublished = CountDownLatch(1)
            val sourceReady = CountDownLatch(1)
            val stateRef = AtomicReference<LinuxVideoPlayerState?>()
            val state =
                LinuxVideoPlayerState(
                    FakeBridge(copyFrames = true),
                    frameDeliveryObserver = { _, published ->
                        if (published && stateRef.get()?.currentFrameState?.value != null) framePublished.countDown()
                    },
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    frameRenderingEnabled = true,
                )
            stateRef.set(state)
            var disposed = false
            try {
                state.openUri("https://example.invalid/published-frame-lifetime.mp4", InitialPlayerState.PLAY)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertTrue(framePublished.await(5, TimeUnit.SECONDS))
                val publishedFrame = assertNotNull(state.currentFrameState.value)
                assertEquals(2, publishedFrame.width)
                assertEquals(2, publishedFrame.height)
                val pixelsBeforeDisposal = IntArray(4)
                publishedFrame.readPixels(pixelsBeforeDisposal)

                state.disposeAsync().get(5, TimeUnit.SECONDS)
                disposed = true

                val pixelsAfterDisposal = IntArray(4)
                publishedFrame.readPixels(pixelsAfterDisposal)
                assertContentEquals(pixelsBeforeDisposal, pixelsAfterDisposal)
            } finally {
                if (!disposed) deferDisposal(state)
            }
        }

    @Test
    fun `newer play between stop pause and seek prevents stale stop seek and reset`() =
        lifecycleTest {
            val stopPaused = CountDownLatch(1)
            val releaseStop = CountDownLatch(1)
            val sourceReady = CountDownLatch(1)
            val stopCompleted = CountDownLatch(1)
            val playCompleted = CountDownLatch(1)
            val playExecuted = CountDownLatch(1)
            val bridge = FakeBridge(onPlay = { playExecuted.countDown() })
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "stop") stopCompleted.countDown()
                        if (operation == "play") playCompleted.countDown()
                    },
                    afterStopPauseForTest = {
                        stopPaused.countDown()
                        check(releaseStop.await(5, TimeUnit.SECONDS))
                    },
                    frameRenderingEnabled = false,
                )
            try {
                state.openUri("https://example.invalid/stop-play.bmp", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                state.stop()
                assertTrue(stopPaused.await(5, TimeUnit.SECONDS))

                state.play()
                releaseStop.countDown()

                assertTrue(stopCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(playExecuted.await(5, TimeUnit.SECONDS))
                assertTrue(playCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(state.hasMedia)
                assertTrue(state.isPlaying)
                assertTrue(bridge.calls().none { it.name == "seek" })
            } finally {
                releaseStop.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `newer play after stop seek prevents stale managed stop commit`() =
        lifecycleTest {
            val sourceReady = CountDownLatch(1)
            val stopCompleted = CountDownLatch(1)
            val playCompleted = CountDownLatch(1)
            val managedCommits = AtomicInteger()
            val bridge = FakeBridge()
            lateinit var state: LinuxVideoPlayerState
            state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "stop") stopCompleted.countDown()
                        if (operation == "play") playCompleted.countDown()
                    },
                    beforeStopManagedCommitForTest = { state.play() },
                    stopManagedCommitCompletedForTest = { managedCommits.incrementAndGet() },
                    frameRenderingEnabled = false,
                )
            try {
                state.openUri("https://example.invalid/stop-managed-commit.bmp", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                state.stop()

                assertTrue(stopCompleted.await(5, TimeUnit.SECONDS))
                assertEquals(1, bridge.calls().count { it.name == "seek" })
                assertEquals(0, managedCommits.get())
                assertTrue(playCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(state.hasMedia)
                assertTrue(state.isPlaying)
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `newer play waits through compound stop seek and managed commit boundary`() =
        lifecycleTest {
            val sourceReady = CountDownLatch(1)
            val initialFrameInstalled = CountDownLatch(1)
            val playAwaitingStop = CountDownLatch(1)
            val stopCommitEntered = CountDownLatch(1)
            val releaseStopCommit = CountDownLatch(1)
            val stopCompleted = CountDownLatch(1)
            val playCompleted = CountDownLatch(1)
            val observePlayAwait = AtomicBoolean()
            val bridge = FakeBridge(blockSeek = true)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    jobTransitionObserverForTest = { kind, event, _ ->
                        if (kind == "frame" && event == "installed") initialFrameInstalled.countDown()
                    },
                    commandAwaitingPredecessorForTest = {
                        if (observePlayAwait.get()) playAwaitingStop.countDown()
                    },
                    beforeStopManagedCommitForTest = {
                        stopCommitEntered.countDown()
                        check(releaseStopCommit.await(5, TimeUnit.SECONDS))
                    },
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "stop") stopCompleted.countDown()
                        if (operation == "play") playCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                state.openUri("https://example.invalid/stop-seek-boundary.mp4", InitialPlayerState.PLAY)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertTrue(initialFrameInstalled.await(5, TimeUnit.SECONDS))
                val playCallsBeforeStop = bridge.calls().count { it.name == "play" }

                state.stop()
                assertTrue(bridge.seekEntered.await(5, TimeUnit.SECONDS))
                observePlayAwait.set(true)
                state.play()
                assertTrue(playAwaitingStop.await(5, TimeUnit.SECONDS))
                assertEquals(playCallsBeforeStop, bridge.calls().count { it.name == "play" })

                bridge.releaseSeek.countDown()
                assertTrue(stopCommitEntered.await(5, TimeUnit.SECONDS))
                assertEquals(playCallsBeforeStop, bridge.calls().count { it.name == "play" })

                releaseStopCommit.countDown()
                assertTrue(stopCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(playCompleted.await(5, TimeUnit.SECONDS))
                assertEquals(playCallsBeforeStop + 1, bridge.calls().count { it.name == "play" })
                assertTrue(state.isPlaying)
            } finally {
                bridge.releaseSeek.countDown()
                releaseStopCommit.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `native stop seek callback can replace source on the same thread`() =
        lifecycleTest {
            val readyGenerations = LinkedBlockingQueue<Long>()
            val stopCompleted = CountDownLatch(1)
            val replaced = AtomicBoolean()
            lateinit var state: LinuxVideoPlayerState
            val bridge =
                FakeBridge(
                    onSeek = {
                        if (replaced.compareAndSet(false, true)) {
                            state.openUri("https://example.invalid/from-stop-callback.mp4", InitialPlayerState.PAUSE)
                        }
                    },
                )
            state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { generation, _ -> readyGenerations.put(generation) },
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "stop") stopCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                state.openUri("https://example.invalid/original.mp4", InitialPlayerState.PLAY)
                val originalGeneration = assertNotNull(readyGenerations.poll(5, TimeUnit.SECONDS))

                state.stop()

                assertTrue(stopCompleted.await(5, TimeUnit.SECONDS))
                val replacementGeneration = assertNotNull(readyGenerations.poll(5, TimeUnit.SECONDS))
                assertTrue(replacementGeneration > originalGeneration)
                assertTrue(replaced.get())
                assertTrue(bridge.calls().any { it.name == "seek" && it.handle == 82L })
                assertEquals(123L, state.withPlayerForTest(replacementGeneration) { it })
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `stop does not reset media when duration prevents seek JNI`() =
        lifecycleTest {
            val sourceReady = CountDownLatch(1)
            val stopCompleted = CountDownLatch(1)
            val bridge = FakeBridge(duration = 0.0)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "stop") stopCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                state.openUri("https://example.invalid/zero-duration.bmp", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                state.sliderPos = 500f
                state.stop()
                assertTrue(stopCompleted.await(5, TimeUnit.SECONDS))

                assertTrue(state.hasMedia)
                assertEquals(500f, state.sliderPos)
                assertFalse(state.isPlaying)
                assertTrue(bridge.calls().none { it.name == "seek" })
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `explicit seek serializes before a newer play through production state`() =
        lifecycleTest {
            val sourceOpened = CountDownLatch(1)
            val seekCompleted = CountDownLatch(1)
            val playCompleted = CountDownLatch(1)
            val bridge = FakeBridge(blockSeek = true)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceOpenCompletedForTest = { _, failure ->
                        check(failure == null)
                        sourceOpened.countDown()
                    },
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "seek") seekCompleted.countDown()
                        if (operation == "play") playCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                state.openUri("https://example.invalid/seek-play.bmp", InitialPlayerState.PAUSE)
                assertTrue(sourceOpened.await(5, TimeUnit.SECONDS))
                state.seekTo(500f)
                assertTrue(bridge.seekEntered.await(5, TimeUnit.SECONDS))
                state.play()
                bridge.releaseSeek.countDown()

                assertTrue(seekCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(playCompleted.await(5, TimeUnit.SECONDS))
                val ordered = bridge.calls().filter { it.name == "seek" || it.name == "play" }
                assertEquals(listOf("seek", "play"), ordered.map { it.name })
                assertTrue(state.isPlaying)
            } finally {
                bridge.releaseSeek.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `newer pause after seek JNI prevents old seek publication and worker install`() =
        lifecycleTest {
            val sourceOpened = CountDownLatch(1)
            val seekNativeCompleted = CountDownLatch(1)
            val releaseSeekPublication = CountDownLatch(1)
            val seekCompleted = CountDownLatch(1)
            val pauseCompleted = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    FakeBridge(),
                    sourceOpenCompletedForTest = { _, failure ->
                        check(failure == null)
                        sourceOpened.countDown()
                    },
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "seek") seekCompleted.countDown()
                        if (operation == "pause") pauseCompleted.countDown()
                    },
                    afterSeekNativeCommandForTest = {
                        seekNativeCompleted.countDown()
                        check(releaseSeekPublication.await(5, TimeUnit.SECONDS))
                    },
                    frameRenderingEnabled = false,
                )
            try {
                state.openUri("https://example.invalid/seek-latest.bmp", InitialPlayerState.PLAY)
                assertTrue(sourceOpened.await(5, TimeUnit.SECONDS))
                state.seekTo(500f)
                assertTrue(seekNativeCompleted.await(5, TimeUnit.SECONDS))
                state.pause()
                releaseSeekPublication.countDown()
                assertTrue(seekCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(pauseCompleted.await(5, TimeUnit.SECONDS))
                assertFalse(state.isPlaying)
                assertFalse(state.seekInProgressForTest())
                assertNull(state.targetSeekTimeForTest())
                assertFalse(state.isLoading)
                assertNull(state.frameJobTicketForTest())
                assertNull(state.bufferingJobTicketForTest())
            } finally {
                releaseSeekPublication.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `seek completion follows terminal reset publication`() =
        lifecycleTest {
            val sourceOpened = CountDownLatch(1)
            val resetEntered = CountDownLatch(1)
            val seekCompleted = CountDownLatch(1)
            val releaseReset = CompletableDeferred<Unit>()
            val events = LinkedBlockingQueue<String>()
            val state =
                LinuxVideoPlayerState(
                    FakeBridge(),
                    sourceOpenCompletedForTest = { _, failure ->
                        check(failure == null)
                        sourceOpened.countDown()
                    },
                    afterSeekNativeCommandForTest = { events.put("native-seek-returned") },
                    awaitSeekTerminalResetForTest = {
                        events.put("terminal-reset-entered")
                        resetEntered.countDown()
                        releaseReset.await()
                        events.put("terminal-reset-released")
                    },
                    afterSeekTerminalResetPublicationForTest = {
                        events.put("terminal-reset-published")
                    },
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "seek") {
                            events.put("seek-completed")
                            seekCompleted.countDown()
                        }
                    },
                    frameRenderingEnabled = false,
                )
            try {
                state.openUri("https://example.invalid/seek-terminal-reset.bmp", InitialPlayerState.PLAY)
                assertTrue(sourceOpened.await(5, TimeUnit.SECONDS))
                state.seekTo(500f)
                assertTrue(resetEntered.await(5, TimeUnit.SECONDS))

                releaseReset.complete(Unit)
                assertTrue(seekCompleted.await(5, TimeUnit.SECONDS))

                assertEquals(
                    listOf(
                        "native-seek-returned",
                        "terminal-reset-entered",
                        "terminal-reset-released",
                        "terminal-reset-published",
                        "seek-completed",
                    ),
                    events.toList(),
                )
                assertFalse(state.isLoading)
                assertFalse(state.seekInProgressForTest())
                assertNull(state.targetSeekTimeForTest())
            } finally {
                releaseReset.complete(Unit)
                deferDisposal(state)
            }
        }

    @Test
    fun `overlapping non-looping EOS polls publish one terminal callback`() =
        lifecycleTest {
            val blockConsumeEnd = AtomicBoolean(false)
            val bridge = FakeBridge(blockConsumeEndFlag = blockConsumeEnd)
            val sourceReady = CountDownLatch(1)
            val openCompleted = CountDownLatch(1)
            val pauseCompleted = CountDownLatch(1)
            val secondPollAwaitingFirst = CountDownLatch(1)
            val ended = AtomicInteger()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    sourceOpenCompletedForTest = { _, _ -> openCompleted.countDown() },
                    commandAwaitingPredecessorForTest = { secondPollAwaitingFirst.countDown() },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "pause") pauseCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            val executor = Executors.newFixedThreadPool(2)
            try {
                state.openUri("https://example.invalid/overlapping-eos-polls.mp4", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertTrue(openCompleted.await(5, TimeUnit.SECONDS))
                state.pause()
                assertTrue(pauseCompleted.await(5, TimeUnit.SECONDS))
                state.onPlaybackEnded = { ended.incrementAndGet() }
                bridge.signalEndForTest()
                blockConsumeEnd.set(true)

                val firstPoll =
                    executor.submit {
                        runBlocking { state.checkLoopingForTest(current = 10.0, duration = 10.0) }
                    }
                assertTrue(bridge.consumeEndEntered.await(5, TimeUnit.SECONDS))
                val secondPoll =
                    executor.submit {
                        runBlocking { state.checkLoopingForTest(current = 10.0, duration = 10.0) }
                    }
                assertTrue(secondPollAwaitingFirst.await(5, TimeUnit.SECONDS))
                bridge.releaseConsumeEnd.countDown()

                firstPoll.get(5, TimeUnit.SECONDS)
                secondPoll.get(5, TimeUnit.SECONDS)
                assertEquals(1, ended.get())
                assertFalse(state.isPlaying)
            } finally {
                bridge.releaseConsumeEnd.countDown()
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `play reserved while non-looping EOS pause is blocked suppresses stale terminal publication`() =
        lifecycleTest {
            val bridge = FakeBridge(blockPause = true, consumeEnd = true)
            val sourceReady = CountDownLatch(1)
            val openCompleted = CountDownLatch(1)
            val playReserved = CountDownLatch(1)
            val playCompleted = CountDownLatch(1)
            val ended = AtomicInteger()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    sourceOpenCompletedForTest = { _, _ -> openCompleted.countDown() },
                    commandReservedBeforeLaunchForTest = { name, _ ->
                        if (name == "play") playReserved.countDown()
                    },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "play") playCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            val executor = Executors.newSingleThreadExecutor()
            try {
                state.openUri("https://example.invalid/blocked-non-loop-eos.mp4", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertTrue(openCompleted.await(5, TimeUnit.SECONDS))
                state.onPlaybackEnded = { ended.incrementAndGet() }

                val eosFuture =
                    executor.submit {
                        runBlocking { state.checkLoopingForTest(current = 10.0, duration = 10.0) }
                    }
                assertTrue(bridge.pauseEntered.await(5, TimeUnit.SECONDS))
                state.play()
                assertTrue(playReserved.await(5, TimeUnit.SECONDS))
                bridge.releasePause.countDown()

                eosFuture.get(5, TimeUnit.SECONDS)
                assertTrue(playCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(state.isPlaying)
                assertEquals(0, ended.get())
            } finally {
                bridge.releasePause.countDown()
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `successful EOS callback replay is serialized through production state`() =
        lifecycleTest {
            val sourceOpened = CountDownLatch(1)
            val playCompleted = CountDownLatch(1)
            val eosAfterCallback = CountDownLatch(1)
            val releaseEosFinalizer = CountDownLatch(1)
            val eosFinalizerCompleted = CountDownLatch(1)
            val replayAwaitingEos = CountDownLatch(1)
            val commandAwaitCount = AtomicInteger()
            val ended = AtomicInteger()
            val ownersAtEosFinalization =
                AtomicReference<Pair<LinuxGenerationJobSlot.Ticket?, LinuxGenerationJobSlot.Ticket?>>()
            val bridge = FakeBridge(consumeEnd = true, blockConsumeEnd = true)
            lateinit var state: LinuxVideoPlayerState
            state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceOpenCompletedForTest = { _, failure ->
                        check(failure == null)
                        sourceOpened.countDown()
                    },
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "play") playCompleted.countDown()
                    },
                    afterPlaybackEndedCallbackForTest = {
                        eosAfterCallback.countDown()
                        check(releaseEosFinalizer.await(5, TimeUnit.SECONDS))
                    },
                    eosFinalizationCompletedForTest = {
                        ownersAtEosFinalization.set(
                            state.frameJobTicketForTest() to state.bufferingJobTicketForTest(),
                        )
                        eosFinalizerCompleted.countDown()
                    },
                    commandAwaitingPredecessorForTest = {
                        if (commandAwaitCount.incrementAndGet() == 1) replayAwaitingEos.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            state.onPlaybackEnded = {
                ended.incrementAndGet()
                state.play()
            }
            try {
                state.openUri("https://example.invalid/eos-replay.bmp", InitialPlayerState.PAUSE)
                assertTrue(sourceOpened.await(5, TimeUnit.SECONDS))
                assertTrue(bridge.consumeEndEntered.await(5, TimeUnit.SECONDS))
                val callsBeforeEos = bridge.calls().size
                val eosFrameOwner = assertNotNull(state.frameJobTicketForTest())
                val eosBufferingOwner = assertNotNull(state.bufferingJobTicketForTest())
                bridge.releaseConsumeEnd.countDown()
                assertTrue(eosAfterCallback.await(5, TimeUnit.SECONDS))
                assertTrue(replayAwaitingEos.await(5, TimeUnit.SECONDS))
                assertEquals(eosFrameOwner, state.frameJobTicketForTest())
                assertEquals(eosBufferingOwner, state.bufferingJobTicketForTest())
                releaseEosFinalizer.countDown()
                assertTrue(eosFinalizerCompleted.await(5, TimeUnit.SECONDS))
                assertEquals(null to null, ownersAtEosFinalization.get())
                assertTrue(playCompleted.await(5, TimeUnit.SECONDS))
                val replayFrameOwner = assertNotNull(state.frameJobTicketForTest())
                val replayBufferingOwner = assertNotNull(state.bufferingJobTicketForTest())

                assertFalse(replayFrameOwner == eosFrameOwner)
                assertFalse(replayBufferingOwner == eosBufferingOwner)
                assertEquals(1, ended.get())
                val stateChangingCalls =
                    bridge.calls().drop(callsBeforeEos).filterNot { it.name == "consumeEnd" }
                assertEquals(
                    listOf("pause", "seek", "play"),
                    stateChangingCalls.map(PlayerCall::name),
                )
                assertTrue(state.isPlaying)
            } finally {
                bridge.releaseConsumeEnd.countDown()
                releaseEosFinalizer.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `throwing EOS callback still releases exact worker owners`() =
        lifecycleTest {
            val sourceOpened = CountDownLatch(1)
            val eosFinalized = CountDownLatch(1)
            val updateFailed = CountDownLatch(1)
            val observedFailure = AtomicReference<Throwable?>()
            val callbackCount = AtomicInteger()
            val callbackFailure = IllegalStateException("callback failure")
            val bridge = FakeBridge(consumeEnd = true, blockConsumeEnd = true)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceOpenCompletedForTest = { _, failure ->
                        check(failure == null)
                        sourceOpened.countDown()
                    },
                    updatePositionFailureForTest = { failure ->
                        observedFailure.set(failure)
                        updateFailed.countDown()
                    },
                    eosFinalizationCompletedForTest = { eosFinalized.countDown() },
                    frameRenderingEnabled = false,
                )
            state.onPlaybackEnded = {
                callbackCount.incrementAndGet()
                throw callbackFailure
            }
            try {
                state.openUri("https://example.invalid/eos-callback-failure.bmp", InitialPlayerState.PAUSE)
                assertTrue(sourceOpened.await(5, TimeUnit.SECONDS))
                assertTrue(bridge.consumeEndEntered.await(5, TimeUnit.SECONDS))
                bridge.releaseConsumeEnd.countDown()
                assertTrue(eosFinalized.await(5, TimeUnit.SECONDS))
                assertTrue(updateFailed.await(5, TimeUnit.SECONDS))

                assertEquals(callbackFailure.message, observedFailure.get()?.message)
                assertEquals(1, callbackCount.get())
                assertFalse(state.isPlaying)
                assertNull(state.frameJobTicketForTest())
                assertNull(state.bufferingJobTicketForTest())
            } finally {
                bridge.releaseConsumeEnd.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `public replay fails closed when completion epoch is exhausted`() =
        lifecycleTest {
            val sourceOpened = CountDownLatch(1)
            val eosFinalized = CountDownLatch(1)
            val playCompleted = CountDownLatch(1)
            val ended = AtomicInteger()
            val bridge = FakeBridge(consumeEnd = true, blockConsumeEnd = true)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceOpenCompletedForTest = { _, failure ->
                        check(failure == null)
                        sourceOpened.countDown()
                    },
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "play") playCompleted.countDown()
                    },
                    eosFinalizationCompletedForTest = { eosFinalized.countDown() },
                    frameRenderingEnabled = false,
                    initialPlaybackCompletionGenerationForTest = Long.MAX_VALUE - 1L,
                )
            state.onPlaybackEnded = { ended.incrementAndGet() }
            try {
                state.openUri("https://example.invalid/eos-exhaustion.bmp", InitialPlayerState.PAUSE)
                assertTrue(sourceOpened.await(5, TimeUnit.SECONDS))
                assertTrue(bridge.consumeEndEntered.await(5, TimeUnit.SECONDS))
                bridge.releaseConsumeEnd.countDown()
                assertTrue(eosFinalized.await(5, TimeUnit.SECONDS))
                val callsBeforeReplay = bridge.calls().size

                state.play()
                assertTrue(playCompleted.await(5, TimeUnit.SECONDS))

                val replayNativeCalls =
                    bridge.calls().drop(callsBeforeReplay).filter { it.name == "seek" || it.name == "play" }
                assertTrue(replayNativeCalls.isEmpty())
                assertEquals(1, ended.get())
                assertFalse(state.isPlaying)
                assertNotNull(state.error)
                assertNull(state.frameJobTicketForTest())
                assertNull(state.bufferingJobTicketForTest())
                deferDisposal(state)
            } finally {
                bridge.releaseConsumeEnd.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `late initial player is rejected after replacement opens`() =
        lifecycleTest {
            val bridge = FakeBridge(blockFirstCreate = true)
            val sourceReady = CountDownLatch(1)
            val initializationCompleted = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    initializationCompletedForTest = { initializationCompleted.countDown() },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.firstCreateEntered.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/replacement.bmp", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                bridge.releaseFirstCreate.countDown()
                assertTrue(initializationCompleted.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(82L), bridge.opened())
                assertTrue(bridge.disposed().contains(41L))
                assertNull(state.error)
                assertFalse(bridge.calls().any { it.handle == 41L })
            } finally {
                bridge.releaseFirstCreate.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `invalid replacement drains the synchronously retired handle`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val openCompleted = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceOpenCompletedForTest = { _, _ -> openCompleted.countDown() },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("/definitely/missing/nuvio-lifecycle-fixture.bmp", InitialPlayerState.PAUSE)
                assertTrue(openCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(bridge.disposed().contains(41L))
                assertTrue(state.error is VideoPlayerError.SourceError)
                assertTrue(bridge.opened().isEmpty())
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `controls after replacement opening can only use the replacement handle`() =
        lifecycleTest {
            val bridge = FakeBridge(blockThirdCreate = true)
            val readySources = LinkedBlockingQueue<Long>()
            val controlCompletions = CountDownLatch(2)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { generation, _ -> readySources.put(generation) },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "pause" || name == "seek") controlCompletions.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/source-a.bmp", InitialPlayerState.PAUSE)
                assertTrue(readySources.poll(5, TimeUnit.SECONDS) != null)
                state.openUri("https://example.invalid/source-b.bmp", InitialPlayerState.PAUSE)
                assertTrue(bridge.thirdCreateEntered.await(5, TimeUnit.SECONDS))
                bridge.releaseThirdCreate.countDown()
                assertTrue(readySources.poll(5, TimeUnit.SECONDS) != null)

                state.pause()
                state.seekTo(500f)
                assertTrue(controlCompletions.await(5, TimeUnit.SECONDS))
                assertTrue(bridge.calls().any { it.handle == 123L && it.name == "pause" })
                assertTrue(bridge.calls().any { it.handle == 123L && it.name == "seek" })
                assertFalse(bridge.calls().any { it.handle == 82L && it.name in setOf("pause", "seek", "play") })
            } finally {
                bridge.releaseThirdCreate.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `delayed initial creation failure cannot overwrite replacement state`() =
        lifecycleTest {
            val bridge = FakeBridge(blockFirstCreate = true, failFirstCreate = true)
            val initializationCompleted = CountDownLatch(1)
            val initializationFailure = AtomicReference<Throwable?>()
            val sourceReady = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    frameRenderingEnabled = false,
                    initializationCompletedForTest = { failure ->
                        initializationFailure.set(failure)
                        initializationCompleted.countDown()
                    },
                )
            try {
                assertTrue(bridge.firstCreateEntered.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/replacement.bmp", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                bridge.releaseFirstCreate.countDown()
                assertTrue(initializationCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(initializationFailure.get() is IllegalStateException)
                assertNull(state.error)
            } finally {
                bridge.releaseFirstCreate.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `play during blocked installation is applied only after JNI player exists`() =
        lifecycleTest {
            val bridge = FakeBridge(blockSecondCreate = true)
            val ready = CountDownLatch(1)
            val desired = AtomicReference<Boolean>()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, play ->
                        desired.set(play)
                        ready.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/opening-play.bmp", InitialPlayerState.PAUSE)
                assertTrue(bridge.secondCreateEntered.await(5, TimeUnit.SECONDS))
                state.play()
                assertFalse(state.isPlaying)
                assertFalse(bridge.calls().any { it.handle == 82L && it.name == "play" })
                bridge.releaseSecondCreate.countDown()
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                assertEquals(true, desired.get())
                assertTrue(bridge.calls().any { it.handle == 82L && it.name == "play" })
                assertTrue(state.isPlaying)
            } finally {
                bridge.releaseSecondCreate.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `current overlapping replacement waits for stale handle destruction and installs`() =
        lifecycleTest {
            val bridge =
                FakeBridge(
                    blockSecondCreate = true,
                    blockThirdCreate = true,
                    blockDisposeHandle = 82L,
                )
            val completedSources = LinkedBlockingQueue<Long>()
            val currentInstallationWaiting = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceOpenCompletedForTest = { generation, failure ->
                        if (failure == null) completedSources.put(generation)
                    },
                    installationWaitingForDrainForTest = { currentInstallationWaiting.countDown() },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))

                state.openUri("https://example.invalid/stale.bmp", InitialPlayerState.PAUSE)
                assertTrue(bridge.secondCreateEntered.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/current.bmp", InitialPlayerState.PAUSE)
                assertTrue(bridge.thirdCreateEntered.await(5, TimeUnit.SECONDS))

                bridge.releaseSecondCreate.countDown()
                assertTrue(bridge.blockedDisposeEntered.await(5, TimeUnit.SECONDS))
                bridge.releaseThirdCreate.countDown()
                assertTrue(bridge.thirdCreateFinished.await(5, TimeUnit.SECONDS))
                assertTrue(currentInstallationWaiting.await(5, TimeUnit.SECONDS))
                assertFalse(bridge.opened().contains(123L))

                bridge.releaseBlockedDispose.countDown()
                val currentGeneration = assertNotNull(completedSources.poll(5, TimeUnit.SECONDS))
                assertTrue(currentGeneration > 0L)
                assertEquals(listOf(123L), bridge.opened().filter { it == 82L || it == 123L })
                assertEquals(123L, state.withPlayerForTest(currentGeneration) { it })
                assertEquals(1, bridge.disposed().count { it == 82L })
            } finally {
                bridge.releaseSecondCreate.countDown()
                bridge.releaseThirdCreate.countDown()
                bridge.releaseBlockedDispose.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `pause during blocked installation prevents initial play publication`() =
        lifecycleTest {
            val bridge = FakeBridge(blockSecondCreate = true)
            val sourceReady = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/opening-pause.bmp", InitialPlayerState.PLAY)
                assertTrue(bridge.secondCreateEntered.await(5, TimeUnit.SECONDS))
                state.pause()
                bridge.releaseSecondCreate.countDown()
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertFalse(state.sourceOpeningForTest())
                assertFalse(state.isPlaying)
                assertFalse(bridge.calls().any { it.handle == 82L && it.name == "play" })
            } finally {
                bridge.releaseSecondCreate.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `dispose during late native creation rejects and destroys created handle`() =
        lifecycleTest {
            val bridge = FakeBridge(blockFirstCreate = true)
            val disposalWorkerStarted = CountDownLatch(1)
            val disposalWaitingForCreation = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    disposalWorkerStartedForTest = { disposalWorkerStarted.countDown() },
                    disposalWaitingForCreationsForTest = { active ->
                        check(active == 1)
                        disposalWaitingForCreation.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            assertTrue(bridge.firstCreateEntered.await(5, TimeUnit.SECONDS))
            val completion = state.disposeAsync()
            assertTrue(disposalWorkerStarted.await(5, TimeUnit.SECONDS))
            assertTrue(disposalWaitingForCreation.await(5, TimeUnit.SECONDS))
            assertFalse(completion.isDone)
            bridge.releaseFirstCreate.countDown()
            completion.get(5, TimeUnit.SECONDS)
            assertEquals(1, bridge.disposed().count { it == 41L })
        }

    @Test
    fun `dispose waits through physical rejected handle destruction`() =
        lifecycleTest {
            val bridge = FakeBridge(blockFirstCreate = true, blockDisposeHandle = 41L)
            val disposalWaitingForCreation = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    disposalWaitingForCreationsForTest = { disposalWaitingForCreation.countDown() },
                    frameRenderingEnabled = false,
                )
            assertTrue(bridge.firstCreateEntered.await(5, TimeUnit.SECONDS))
            val completion = state.disposeAsync()
            assertTrue(disposalWaitingForCreation.await(5, TimeUnit.SECONDS))
            bridge.releaseFirstCreate.countDown()
            assertTrue(bridge.blockedDisposeEntered.await(5, TimeUnit.SECONDS))
            assertFalse(completion.isDone)
            bridge.releaseBlockedDispose.countDown()
            completion.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(41L), bridge.disposed())
        }

    @Test
    fun `public disposal API awaits physical native destruction`() =
        lifecycleTest {
            val bridge = FakeBridge(blockDisposeHandle = 41L)
            val state: VideoPlayerState = LinuxVideoPlayerState(bridge, frameRenderingEnabled = false)
            val executor = Executors.newSingleThreadExecutor()
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                val disposal = executor.submit<Unit> { runBlocking { state.disposeAndAwait() } }
                assertTrue(bridge.blockedDisposeEntered.await(5, TimeUnit.SECONDS))
                assertFalse(disposal.isDone)
                bridge.releaseBlockedDispose.countDown()
                disposal.get(5, TimeUnit.SECONDS)
                assertEquals(listOf(41L), bridge.disposed())
            } finally {
                bridge.releaseBlockedDispose.countDown()
                executor.shutdownNow()
            }
        }

    @Test
    fun `default JVM wrapper awaits delegate physical native destruction`() =
        lifecycleTest {
            val bridge = FakeBridge(blockDisposeHandle = 41L)
            val delegate = LinuxVideoPlayerState(bridge, frameRenderingEnabled = false)
            val state: VideoPlayerState = DefaultVideoPlayerState(delegate)
            val executor = Executors.newSingleThreadExecutor()
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                val disposal = executor.submit<Unit> { runBlocking { state.disposeAndAwait() } }
                assertTrue(bridge.blockedDisposeEntered.await(5, TimeUnit.SECONDS))
                assertFalse(disposal.isDone)
                bridge.releaseBlockedDispose.countDown()
                disposal.get(5, TimeUnit.SECONDS)
                assertEquals(listOf(41L), bridge.disposed())
            } finally {
                bridge.releaseBlockedDispose.countDown()
                executor.shutdownNow()
            }
        }

    @Test
    fun `default JVM wrapper propagates delegate disposal failure`() =
        lifecycleTest {
            val bridge = FakeBridge(failDisposeHandle = 41L)
            val delegate = LinuxVideoPlayerState(bridge, frameRenderingEnabled = false)
            val state: VideoPlayerState = DefaultVideoPlayerState(delegate)
            assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
            val failure = runCatching { state.disposeAndAwait() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(listOf(41L), bridge.disposed())
        }

    @Test
    fun `dispose completion waits for managed close boundary`() =
        lifecycleTest {
            val managedCloseEntered = CountDownLatch(1)
            val releaseManagedClose = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    FakeBridge(),
                    managedResourceCloseForTest = { index ->
                        if (index == 0) {
                            managedCloseEntered.countDown()
                            check(releaseManagedClose.await(5, TimeUnit.SECONDS))
                        }
                    },
                    frameRenderingEnabled = false,
                )
            try {
                val completion = state.disposeAsync()
                assertTrue(managedCloseEntered.await(5, TimeUnit.SECONDS))
                assertFalse(completion.isDone)
                releaseManagedClose.countDown()
                completion.get(5, TimeUnit.SECONDS)
            } finally {
                releaseManagedClose.countDown()
            }
        }

    @Test
    fun `managed cleanup failure still attempts sibling cleanup and native drain`() =
        lifecycleTest {
            val secondCleanupAttempted = AtomicBoolean(false)
            val bridge = FakeBridge()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    managedResourceCloseForTest = { index ->
                        if (index == 0) error("first managed cleanup failed")
                        secondCleanupAttempted.set(true)
                    },
                    frameRenderingEnabled = false,
                )
            assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))

            val failure = assertFailsWith<Exception> { state.disposeAsync().get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is IllegalStateException)
            assertTrue(secondCleanupAttempted.get())
            assertEquals(listOf(41L), bridge.disposed())
        }

    @Test
    fun `rejected late creation destruction failure fails disposal and poisons ownership`() =
        lifecycleTest {
            val bridge = FakeBridge(blockFirstCreate = true, failEveryDispose = true)
            val disposalWaitingForCreation = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    disposalWaitingForCreationsForTest = { disposalWaitingForCreation.countDown() },
                    frameRenderingEnabled = false,
                )
            assertTrue(bridge.firstCreateEntered.await(5, TimeUnit.SECONDS))
            val completion = state.disposeAsync()
            assertTrue(disposalWaitingForCreation.await(5, TimeUnit.SECONDS))
            bridge.releaseFirstCreate.countDown()

            val failure = assertFailsWith<Exception> { completion.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is IllegalStateException)
            assertEquals(listOf(41L), bridge.disposed())
            assertNull(state.withPlayerForTest(state.captureSourceGenerationForTest()) { it })
        }

    @Test
    fun `dispose closes open registration and joins every overlapping open job`() =
        lifecycleTest {
            val bridge = FakeBridge(blockSecondCreate = true, blockThirdCreate = true)
            val openJobsSnapshotted = CountDownLatch(1)
            val waitingForTwoCreations = CountDownLatch(1)
            val joiningTwoOpenJobs = CountDownLatch(1)
            val openJobWaitRegistered = CountDownLatch(1)
            val joinedTwoOpenJobs = CountDownLatch(1)
            val openCallbacksEntered = CountDownLatch(2)
            val releaseOpenCallbacks = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceOpenCompletedForTest = { _, _ ->
                        openCallbacksEntered.countDown()
                        check(releaseOpenCallbacks.await(5, TimeUnit.SECONDS))
                    },
                    disposalWaitingForCreationsForTest = { count ->
                        if (count == 2) waitingForTwoCreations.countDown()
                    },
                    disposalOpenJobsSnapshottedForTest = { count ->
                        check(count == 2)
                        openJobsSnapshotted.countDown()
                    },
                    disposalJoiningOpenJobsForTest = { count ->
                        check(count == 2)
                        joiningTwoOpenJobs.countDown()
                    },
                    disposalJobWaitRegisteredForTest = { boundary ->
                        if (boundary == DisposalWaitBoundary.OPEN_JOBS) openJobWaitRegistered.countDown()
                    },
                    disposalOpenJobsJoinedForTest = { count ->
                        check(count == 2)
                        joinedTwoOpenJobs.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/a.bmp", InitialPlayerState.PAUSE)
                assertTrue(bridge.secondCreateEntered.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/b.bmp", InitialPlayerState.PAUSE)
                assertTrue(bridge.thirdCreateEntered.await(5, TimeUnit.SECONDS))

                val completion = state.disposeAsync()
                assertTrue(openJobsSnapshotted.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/late.bmp", InitialPlayerState.PAUSE)
                assertEquals(3, bridge.createCount.get())
                assertTrue(waitingForTwoCreations.await(5, TimeUnit.SECONDS))
                state.awaitCreationQuiescenceWaitEnteredForTest()

                bridge.releaseSecondCreate.countDown()
                bridge.releaseThirdCreate.countDown()
                assertTrue(openCallbacksEntered.await(5, TimeUnit.SECONDS))
                assertTrue(joiningTwoOpenJobs.await(5, TimeUnit.SECONDS))
                assertTrue(openJobWaitRegistered.await(5, TimeUnit.SECONDS))
                assertEquals(1L, joinedTwoOpenJobs.count)
                assertFalse(completion.isDone)
                releaseOpenCallbacks.countDown()
                assertTrue(joinedTwoOpenJobs.await(5, TimeUnit.SECONDS))
                completion.get(5, TimeUnit.SECONDS)
                assertEquals(3, bridge.createCount.get())
                assertEquals(1, bridge.disposed().count { it == 82L })
                assertEquals(1, bridge.disposed().count { it == 123L })
            } finally {
                bridge.releaseSecondCreate.countDown()
                bridge.releaseThirdCreate.countDown()
                releaseOpenCallbacks.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `cancelled opening autoplay before command body settles successor and disposal`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val openingCommandReserved = CountDownLatch(1)
            val releaseOpeningCommand = CountDownLatch(1)
            val successorAwaitingOpening = CountDownLatch(1)
            val ioRootCancelled = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    beforeOpeningIntentApplyForTest = { _, desiredPlaying ->
                        check(desiredPlaying)
                        openingCommandReserved.countDown()
                        check(releaseOpeningCommand.await(5, TimeUnit.SECONDS))
                    },
                    commandAwaitingPredecessorForTest = {
                        successorAwaitingOpening.countDown()
                    },
                    disposalIoRootCancelledForTest = { ioRootCancelled.countDown() },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/open-autoplay-cancel.bmp", InitialPlayerState.PLAY)
                assertTrue(openingCommandReserved.await(5, TimeUnit.SECONDS))
                state.pause()
                assertTrue(successorAwaitingOpening.await(5, TimeUnit.SECONDS))

                val completion = state.disposeAsync()
                assertTrue(ioRootCancelled.await(5, TimeUnit.SECONDS))
                releaseOpeningCommand.countDown()
                completion.get(5, TimeUnit.SECONDS)

                assertEquals(listOf(41L, 82L), bridge.disposed())
            } finally {
                releaseOpeningCommand.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `failed play open settles command chain before pause and disposal`() =
        lifecycleTest {
            val pauseCompleted = CountDownLatch(1)
            val openCompleted = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    FakeBridge(),
                    sourceOpenCompletedForTest = { _, _ -> openCompleted.countDown() },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "pause") pauseCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                state.openUri("/definitely/missing/nuvio-candidate10.mp4", InitialPlayerState.PLAY)
                assertTrue(openCompleted.await(5, TimeUnit.SECONDS))
                state.pause()
                assertTrue(pauseCompleted.await(5, TimeUnit.SECONDS))
                state.disposeAsync().get(5, TimeUnit.SECONDS)
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `no media play settles reserved command before successor pause`() =
        lifecycleTest {
            val pauseCompleted = CountDownLatch(1)
            val initialOpenCompleted = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    FakeBridge(),
                    sourceOpenCompletedForTest = { _, _ -> initialOpenCompleted.countDown() },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "pause") pauseCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                state.openUri("/definitely/missing/nuvio-candidate10-reopen.mp4", InitialPlayerState.PAUSE)
                assertTrue(initialOpenCompleted.await(5, TimeUnit.SECONDS))
                state.play()
                state.pause()
                assertTrue(pauseCompleted.await(5, TimeUnit.SECONDS))
                state.disposeAsync().get(5, TimeUnit.SECONDS)
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `looping EOS poll waits for in-flight seek before consuming native end`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val sourceReady = CountDownLatch(1)
            val openCompleted = CountDownLatch(1)
            val seekAtTerminalReset = CountDownLatch(1)
            val releaseSeek = CountDownLatch(1)
            val pollAwaitingSeek = CountDownLatch(1)
            val seekCompleted = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    sourceOpenCompletedForTest = { _, _ -> openCompleted.countDown() },
                    afterSeekNativeCommandForTest = {
                        seekAtTerminalReset.countDown()
                        check(releaseSeek.await(5, TimeUnit.SECONDS))
                    },
                    commandAwaitingPredecessorForTest = { pollAwaitingSeek.countDown() },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "seek") seekCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            val executor = Executors.newSingleThreadExecutor()
            var pollFuture: java.util.concurrent.Future<*>? = null
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/empty-loop-poll.mp4", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertTrue(openCompleted.await(5, TimeUnit.SECONDS))

                state.seekTo(500f)
                assertTrue(seekAtTerminalReset.await(5, TimeUnit.SECONDS))
                val consumeCallsBeforePoll = bridge.calls().count { it.name == "consumeEnd" }
                state.loop = true
                pollFuture =
                    executor.submit {
                        runBlocking { state.checkLoopingForTest(current = 3.0, duration = 10.0) }
                    }
                assertTrue(pollAwaitingSeek.await(5, TimeUnit.SECONDS))
                assertEquals(consumeCallsBeforePoll, bridge.calls().count { it.name == "consumeEnd" })
                releaseSeek.countDown()

                assertTrue(seekCompleted.await(5, TimeUnit.SECONDS))
                assertNotNull(pollFuture).get(5, TimeUnit.SECONDS)
                assertFalse(state.isLoading)
                assertFalse(state.seekInProgressForTest())
                assertNull(state.targetSeekTimeForTest())
            } finally {
                releaseSeek.countDown()
                runCatching { pollFuture?.get(5, TimeUnit.SECONDS) }
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `overlapping looping EOS polls publish one restart`() =
        lifecycleTest {
            val bridge = FakeBridge(blockSeek = true)
            val sourceReady = CountDownLatch(1)
            val openCompleted = CountDownLatch(1)
            val restarted = AtomicInteger()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    sourceOpenCompletedForTest = { _, _ -> openCompleted.countDown() },
                    frameRenderingEnabled = false,
                )
            val executor = Executors.newFixedThreadPool(2)
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/overlapping-loop-polls.mp4", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertTrue(openCompleted.await(5, TimeUnit.SECONDS))
                state.loop = true
                state.onRestart = { restarted.incrementAndGet() }
                val seekCallsBefore = bridge.calls().count { it.name == "seek" }
                bridge.signalEndForTest()

                val firstPoll =
                    executor.submit {
                        runBlocking { state.checkLoopingForTest(current = 10.0, duration = 10.0) }
                    }
                assertTrue(bridge.seekEntered.await(5, TimeUnit.SECONDS))
                val secondPoll =
                    executor.submit {
                        runBlocking { state.checkLoopingForTest(current = 10.0, duration = 10.0) }
                    }
                bridge.releaseSeek.countDown()

                firstPoll.get(5, TimeUnit.SECONDS)
                secondPoll.get(5, TimeUnit.SECONDS)
                assertEquals(seekCallsBefore + 1, bridge.calls().count { it.name == "seek" })
                assertEquals(1, restarted.get())
            } finally {
                bridge.releaseSeek.countDown()
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `superseded loop restart does not seek or publish restart callback`() =
        lifecycleTest {
            val blockConsumeEnd = AtomicBoolean(false)
            val bridge = FakeBridge(blockConsumeEndFlag = blockConsumeEnd)
            val sourceReady = CountDownLatch(1)
            val openCompleted = CountDownLatch(1)
            val pauseCompleted = CountDownLatch(1)
            val playReserved = CountDownLatch(1)
            val playCompleted = CountDownLatch(1)
            val restartCount = AtomicInteger()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    sourceOpenCompletedForTest = { _, _ -> openCompleted.countDown() },
                    commandReservedBeforeLaunchForTest = { name, _ ->
                        if (name == "play") playReserved.countDown()
                    },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "pause") pauseCompleted.countDown()
                        if (name == "play") playCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            val executor = Executors.newSingleThreadExecutor()
            try {
                state.openUri("https://example.invalid/superseded-loop.mp4", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertTrue(openCompleted.await(5, TimeUnit.SECONDS))
                state.pause()
                assertTrue(pauseCompleted.await(5, TimeUnit.SECONDS))
                state.loop = true
                state.onRestart = { restartCount.incrementAndGet() }
                val seekCallsBefore = bridge.calls().count { it.name == "seek" }
                bridge.signalEndForTest()
                blockConsumeEnd.set(true)

                val loopFuture =
                    executor.submit {
                        runBlocking { state.checkLoopingForTest(current = 10.0, duration = 10.0) }
                    }
                assertTrue(bridge.consumeEndEntered.await(5, TimeUnit.SECONDS))
                state.play()
                assertTrue(playReserved.await(5, TimeUnit.SECONDS))
                bridge.releaseConsumeEnd.countDown()

                loopFuture.get(5, TimeUnit.SECONDS)
                assertTrue(playCompleted.await(5, TimeUnit.SECONDS))
                assertEquals(seekCallsBefore, bridge.calls().count { it.name == "seek" })
                assertEquals(0, restartCount.get())
            } finally {
                bridge.releaseConsumeEnd.countDown()
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `frame worker successful loop restart preserves owner and publishes callback`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val sourceReady = CountDownLatch(1)
            val openCompleted = CountDownLatch(1)
            val restarted = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    sourceOpenCompletedForTest = { _, _ -> openCompleted.countDown() },
                    frameRenderingEnabled = false,
                )
            try {
                state.loop = true
                state.onRestart = { restarted.countDown() }
                state.openUri("https://example.invalid/frame-loop-success.mp4", InitialPlayerState.PLAY)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertTrue(openCompleted.await(5, TimeUnit.SECONDS))
                val frameOwnerBeforeRestart = assertNotNull(state.frameJobTicketForTest())

                bridge.signalEndForTest()
                assertTrue(restarted.await(5, TimeUnit.SECONDS))
                val frameOwnerAfterRestart = assertNotNull(state.frameJobTicketForTest())
                assertEquals(frameOwnerBeforeRestart, frameOwnerAfterRestart)
                assertTrue(bridge.calls().any { it.name == "seek" })
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `frame worker loop callback is suppressed when newer seek reserves at final callback boundary`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val sourceReady = CountDownLatch(1)
            val openCompleted = CountDownLatch(1)
            val loopSeekReachedCallbackBoundary = CountDownLatch(1)
            val releaseLoopSeek = CountDownLatch(1)
            val loopCallbackBoundaryClaimed = AtomicBoolean(false)
            val newerSeekReserved = CountDownLatch(1)
            val newerSeekWaitRegistered = CountDownLatch(1)
            val newerSeekCompleted = CountDownLatch(1)
            val restartCount = AtomicInteger()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    sourceOpenCompletedForTest = { _, _ -> openCompleted.countDown() },
                    awaitSeekTerminalResetForTest = {},
                    beforeSeekSuccessCallbackDispatchForTest = {
                        if (loopCallbackBoundaryClaimed.compareAndSet(false, true)) {
                            loopSeekReachedCallbackBoundary.countDown()
                            check(releaseLoopSeek.await(5, TimeUnit.SECONDS))
                        }
                    },
                    commandReservedBeforeLaunchForTest = { name, _ ->
                        if (name == "seek") newerSeekReserved.countDown()
                    },
                    commandAwaitingPredecessorForTest = { newerSeekWaitRegistered.countDown() },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "seek") newerSeekCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                state.loop = true
                state.onRestart = { restartCount.incrementAndGet() }
                state.openUri("https://example.invalid/frame-loop-superseded.mp4", InitialPlayerState.PLAY)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertTrue(openCompleted.await(5, TimeUnit.SECONDS))

                bridge.signalEndForTest()
                assertTrue(loopSeekReachedCallbackBoundary.await(5, TimeUnit.SECONDS))
                state.seekTo(500f)
                assertTrue(newerSeekReserved.await(5, TimeUnit.SECONDS))
                assertTrue(newerSeekWaitRegistered.await(5, TimeUnit.SECONDS))
                releaseLoopSeek.countDown()
                assertTrue(newerSeekCompleted.await(5, TimeUnit.SECONDS))
                assertEquals(0, restartCount.get())
            } finally {
                releaseLoopSeek.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `failed superseding public commands terminalize stale seek state before settlement`() =
        lifecycleTest {
            listOf("play", "pause", "stop", "seek").forEach { targetCommand ->
                val failPlay = AtomicBoolean(false)
                val failPause = AtomicBoolean(false)
                val duration = AtomicReference(10.0)
                val bridge = FakeBridge(failPlay = failPlay, failPause = failPause, durationValue = duration)
                val sourceReady = CountDownLatch(1)
                val openCompleted = CountDownLatch(1)
                val oldSeekAtReset = CountDownLatch(1)
                val releaseOldSeek = CountDownLatch(1)
                val newerWaitRegistered = CountDownLatch(1)
                val newerTerminalized = CountDownLatch(1)
                val newerCompleted = CountDownLatch(1)
                val seekCompletions = AtomicInteger()
                val state =
                    LinuxVideoPlayerState(
                        bridge,
                        sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                        sourceOpenCompletedForTest = { _, _ -> openCompleted.countDown() },
                        awaitSeekTerminalResetForTest = {
                            oldSeekAtReset.countDown()
                            releaseOldSeek.await()
                        },
                        commandAwaitingPredecessorForTest = { newerWaitRegistered.countDown() },
                        commandTerminalizedForTest = { newerTerminalized.countDown() },
                        asyncOperationCompletedForTest = { name, _ ->
                            if (targetCommand == "seek" && name == "seek") {
                                if (seekCompletions.incrementAndGet() == 2) newerCompleted.countDown()
                            } else if (name == targetCommand) {
                                newerCompleted.countDown()
                            }
                        },
                        frameRenderingEnabled = false,
                    )
                try {
                    state.openUri("https://example.invalid/stale-seek-$targetCommand.mp4", InitialPlayerState.PLAY)
                    assertTrue(sourceReady.await(5, TimeUnit.SECONDS), targetCommand)
                    assertTrue(openCompleted.await(5, TimeUnit.SECONDS), targetCommand)
                    state.seekTo(500f)
                    assertTrue(oldSeekAtReset.await(5, TimeUnit.SECONDS), targetCommand)
                    assertTrue(state.seekInProgressForTest(), targetCommand)
                    assertNotNull(state.targetSeekTimeForTest(), targetCommand)

                    when (targetCommand) {
                        "play" -> {
                            failPlay.set(true)
                            state.play()
                        }

                        "pause" -> {
                            failPause.set(true)
                            state.pause()
                        }

                        "stop" -> {
                            state.stop()
                        }

                        "seek" -> {
                            duration.set(0.0)
                            state.seekTo(250f)
                        }
                    }
                    assertTrue(newerWaitRegistered.await(5, TimeUnit.SECONDS), targetCommand)
                    if (targetCommand == "stop") state.retirePlayerForTest()
                    releaseOldSeek.countDown()

                    assertTrue(newerTerminalized.await(5, TimeUnit.SECONDS), targetCommand)
                    assertTrue(newerCompleted.await(5, TimeUnit.SECONDS), targetCommand)
                    assertFalse(state.seekInProgressForTest(), targetCommand)
                    assertNull(state.targetSeekTimeForTest(), targetCommand)
                    assertFalse(state.isLoading, targetCommand)
                } finally {
                    releaseOldSeek.countDown()
                    deferDisposal(state)
                }
            }
        }

    @Test
    fun `cancelled command before launch body settles successor and disposal`() =
        lifecycleTest {
            listOf("play", "pause", "stop", "seek").forEach { targetCommand ->
                val bridge = FakeBridge()
                val sourceReady = CountDownLatch(1)
                val openCompleted = CountDownLatch(1)
                val commandReserved = CountDownLatch(1)
                val releaseCommandLaunch = CountDownLatch(1)
                val successorAwaitingCommand = CountDownLatch(1)
                val ioRootCancelled = CountDownLatch(1)
                val state =
                    LinuxVideoPlayerState(
                        bridge,
                        sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                        sourceOpenCompletedForTest = { _, _ -> openCompleted.countDown() },
                        commandAwaitingPredecessorForTest = { successorAwaitingCommand.countDown() },
                        disposalIoRootCancelledForTest = { ioRootCancelled.countDown() },
                        frameRenderingEnabled = false,
                        commandReservedBeforeLaunchForTest = { name, _ ->
                            if (name == targetCommand) {
                                commandReserved.countDown()
                                check(releaseCommandLaunch.await(5, TimeUnit.SECONDS))
                            }
                        },
                    )
                val executor = Executors.newSingleThreadExecutor()
                try {
                    assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS), targetCommand)
                    state.openUri("https://example.invalid/reserve-$targetCommand.mp4", InitialPlayerState.PAUSE)
                    assertTrue(sourceReady.await(5, TimeUnit.SECONDS), targetCommand)
                    assertTrue(openCompleted.await(5, TimeUnit.SECONDS), targetCommand)

                    val targetCall =
                        executor.submit {
                            when (targetCommand) {
                                "play" -> state.play()
                                "pause" -> state.pause()
                                "stop" -> state.stop()
                                "seek" -> state.seekTo(500f)
                            }
                        }
                    assertTrue(commandReserved.await(5, TimeUnit.SECONDS), targetCommand)
                    if (targetCommand == "pause") state.play() else state.pause()
                    assertTrue(successorAwaitingCommand.await(5, TimeUnit.SECONDS), targetCommand)
                    val disposal = state.disposeAsync()
                    assertTrue(ioRootCancelled.await(5, TimeUnit.SECONDS), targetCommand)
                    releaseCommandLaunch.countDown()
                    targetCall.get(5, TimeUnit.SECONDS)

                    disposal.get(5, TimeUnit.SECONDS)
                    assertEquals(listOf(41L, 82L), bridge.disposed(), targetCommand)
                } finally {
                    releaseCommandLaunch.countDown()
                    executor.shutdownNow()
                }
            }
        }

    @Test
    fun `synchronous failure after public command reservation settles successor and disposal`() =
        lifecycleTest {
            listOf("play", "pause", "stop", "seek").forEach { targetCommand ->
                val sourceReady = CountDownLatch(1)
                val openCompleted = CountDownLatch(1)
                val predecessorStarted = CountDownLatch(1)
                val releasePredecessor = CountDownLatch(1)
                val successorWaitRegistered = CountDownLatch(1)
                val successorCompleted = CountDownLatch(1)
                val blockFirstPause = AtomicBoolean(true)
                val throwArmed = AtomicBoolean(false)
                val rejection = IllegalStateException("reserved $targetCommand")
                val state =
                    LinuxVideoPlayerState(
                        FakeBridge(),
                        sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                        sourceOpenCompletedForTest = { _, _ -> openCompleted.countDown() },
                        beforeAsyncOperationForTest = { name, _ ->
                            if (name == "pause" && blockFirstPause.compareAndSet(true, false)) {
                                predecessorStarted.countDown()
                                check(releasePredecessor.await(5, TimeUnit.SECONDS))
                            }
                        },
                        commandAwaitingPredecessorForTest = { successorWaitRegistered.countDown() },
                        commandReservedBeforeLaunchForTest = { name, _ ->
                            if (name == targetCommand && throwArmed.compareAndSet(true, false)) throw rejection
                        },
                        asyncOperationCompletedForTest = { name, _ ->
                            if (name == "play") successorCompleted.countDown()
                        },
                        frameRenderingEnabled = false,
                    )
                try {
                    state.openUri("https://example.invalid/sync-$targetCommand.mp4", InitialPlayerState.PAUSE)
                    assertTrue(sourceReady.await(5, TimeUnit.SECONDS), targetCommand)
                    assertTrue(openCompleted.await(5, TimeUnit.SECONDS), targetCommand)

                    state.pause()
                    assertTrue(predecessorStarted.await(5, TimeUnit.SECONDS), targetCommand)
                    throwArmed.set(true)
                    val failure =
                        assertFailsWith<IllegalStateException>(targetCommand) {
                            when (targetCommand) {
                                "play" -> state.play()
                                "pause" -> state.pause()
                                "stop" -> state.stop()
                                "seek" -> state.seekTo(500f)
                            }
                        }
                    assertSame(rejection, failure, targetCommand)

                    state.play()
                    assertTrue(successorWaitRegistered.await(5, TimeUnit.SECONDS), targetCommand)
                    assertEquals(1L, successorCompleted.count, targetCommand)
                    releasePredecessor.countDown()
                    assertTrue(successorCompleted.await(5, TimeUnit.SECONDS), targetCommand)
                    deferDisposal(state)
                } finally {
                    releasePredecessor.countDown()
                    deferDisposal(state)
                }
            }
        }

    @Test
    fun `synchronous opening autoplay reservation failure settles successor`() =
        lifecycleTest {
            val openCompleted = CountDownLatch(1)
            val pauseCompleted = CountDownLatch(1)
            val rejection = IllegalStateException("opening autoplay reservation")
            val bridge = FakeBridge()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    commandReservedBeforeLaunchForTest = { name, _ ->
                        if (name == "open-autoplay") throw rejection
                    },
                    sourceOpenCompletedForTest = { _, _ -> openCompleted.countDown() },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "pause") pauseCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                state.openUri("https://example.invalid/sync-open-autoplay.mp4", InitialPlayerState.PLAY)
                assertTrue(openCompleted.await(5, TimeUnit.SECONDS))
                state.pause()
                assertTrue(pauseCompleted.await(5, TimeUnit.SECONDS))
                assertEquals(0, bridge.calls().count { it.name == "play" })
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `schedule then throw disposal waits for unique owner and native destruction`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val detached = CountDownLatch(1)
            val releaseOwner = CountDownLatch(1)
            val rejection = RejectedExecutionException("scheduled then rejected")
            val executor = Executors.newSingleThreadExecutor()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    managedResourcesDetachedForTest = {
                        detached.countDown()
                        check(releaseOwner.await(5, TimeUnit.SECONDS))
                    },
                    disposalWorkerLauncher = { task ->
                        executor.execute(task)
                        check(detached.await(5, TimeUnit.SECONDS))
                        throw rejection
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                val completion = state.disposeAsync()

                assertFalse(completion.isDone)
                assertTrue(bridge.disposed().isEmpty())
                releaseOwner.countDown()

                val failure =
                    assertFailsWith<java.util.concurrent.ExecutionException> {
                        completion.get(5, TimeUnit.SECONDS)
                    }
                assertSame(rejection, failure.cause)
                assertEquals(listOf(41L), bridge.disposed())
            } finally {
                releaseOwner.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `diagnostic job wait observer failure does not abort operation root drainage`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val operationEntered = CountDownLatch(1)
            val releaseOperation = CountDownLatch(1)
            val operationCompleted = CountDownLatch(1)
            val observerCalled = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    beforeAsyncOperationForTest = { name, _ ->
                        if (name == "volume") {
                            operationEntered.countDown()
                            check(releaseOperation.await(5, TimeUnit.SECONDS))
                        }
                    },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "volume") operationCompleted.countDown()
                    },
                    disposalJobWaitRegisteredForTest = { boundary ->
                        if (boundary == DisposalWaitBoundary.OPERATION_ROOT) {
                            observerCalled.countDown()
                            error("diagnostic observer failure")
                        }
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.volume = 0.5f
                assertTrue(operationEntered.await(5, TimeUnit.SECONDS))

                val completion = state.disposeAsync()
                assertTrue(observerCalled.await(5, TimeUnit.SECONDS))
                assertFalse(completion.isDone)

                releaseOperation.countDown()
                assertTrue(operationCompleted.await(5, TimeUnit.SECONDS))
                completion.get(5, TimeUnit.SECONDS)
                assertEquals(listOf(41L), bridge.disposed())
            } finally {
                releaseOperation.countDown()
            }
        }

    @Test
    fun `interrupted operation root join retries until exact child is terminal`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val operationEntered = CountDownLatch(1)
            val releaseOperation = CountDownLatch(1)
            val operationCompleted = CountDownLatch(1)
            val rootWaitRegistered = CountDownLatch(1)
            val rootRetryRegistered = CountDownLatch(1)
            val rootWaitInterrupted = CountDownLatch(1)
            val rootRegistrationCount = AtomicInteger()
            val disposalOwner = AtomicReference<Thread?>()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    beforeAsyncOperationForTest = { name, _ ->
                        if (name == "volume") {
                            operationEntered.countDown()
                            check(releaseOperation.await(5, TimeUnit.SECONDS))
                        }
                    },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "volume") operationCompleted.countDown()
                    },
                    disposalWorkerStartedForTest = { disposalOwner.set(Thread.currentThread()) },
                    disposalJobWaitRegisteredForTest = { boundary ->
                        if (boundary == DisposalWaitBoundary.OPERATION_ROOT) {
                            when (rootRegistrationCount.incrementAndGet()) {
                                1 -> rootWaitRegistered.countDown()
                                2 -> rootRetryRegistered.countDown()
                            }
                        }
                    },
                    disposalWaitInterruptedForTest = { boundary, _ ->
                        if (boundary == DisposalWaitBoundary.OPERATION_ROOT) rootWaitInterrupted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.volume = 0.5f
                assertTrue(operationEntered.await(5, TimeUnit.SECONDS))

                val completion = state.disposeAsync()
                assertTrue(rootWaitRegistered.await(5, TimeUnit.SECONDS))
                requireNotNull(disposalOwner.get()).interrupt()
                assertTrue(rootWaitInterrupted.await(5, TimeUnit.SECONDS))
                assertTrue(rootRetryRegistered.await(5, TimeUnit.SECONDS))
                assertFalse(completion.isDone)

                releaseOperation.countDown()
                assertTrue(operationCompleted.await(5, TimeUnit.SECONDS))
                val failure =
                    assertFailsWith<java.util.concurrent.ExecutionException> {
                        completion.get(5, TimeUnit.SECONDS)
                    }
                assertTrue(failure.cause is InterruptedException)
                assertEquals(listOf(41L), bridge.disposed())
            } finally {
                releaseOperation.countDown()
            }
        }

    @Test
    fun `interrupted disposal caller cannot skip cleanup worker launch`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val transitionWaiting = CountDownLatch(1)
            val lifecycle =
                LinuxPlayerLifecycle(transitionWaitingForCallbacksForTest = { transitionWaiting.countDown() })
            val state = LinuxVideoPlayerState(bridge, lifecycle, frameRenderingEnabled = false)
            val executor = Executors.newFixedThreadPool(2)
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                val callback =
                    executor.submit<Boolean> {
                        state.invokeCallbackForTest {
                            callbackEntered.countDown()
                            check(releaseCallback.await(5, TimeUnit.SECONDS))
                        }
                    }
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
                val disposalCall =
                    executor.submit<CompletableFuture<Unit>> {
                        Thread.currentThread().interrupt()
                        try {
                            state.disposeAsync()
                        } finally {
                            Thread.interrupted()
                        }
                    }
                val disposal = disposalCall.get(5, TimeUnit.SECONDS)
                assertTrue(transitionWaiting.await(5, TimeUnit.SECONDS))
                releaseCallback.countDown()
                assertTrue(callback.get(5, TimeUnit.SECONDS))
                disposal.get(5, TimeUnit.SECONDS)
                assertEquals(listOf(41L), bridge.disposed())
            } finally {
                releaseCallback.countDown()
                executor.shutdownNow()
            }
        }

    @Test
    fun `interrupted disposal worker still closes lifecycle and destroys native player`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val transitionWaiting = CountDownLatch(1)
            val interruptionObserved = CountDownLatch(1)
            val workerThread = AtomicReference<Thread?>()
            val lifecycle =
                LinuxPlayerLifecycle(transitionWaitingForCallbacksForTest = { transitionWaiting.countDown() })
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    lifecycle,
                    disposalWorkerStartedForTest = { workerThread.set(Thread.currentThread()) },
                    disposalInterruptedForTest = { interruptionObserved.countDown() },
                    frameRenderingEnabled = false,
                )
            val executor = Executors.newSingleThreadExecutor()
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                val callback =
                    executor.submit<Boolean> {
                        state.invokeCallbackForTest {
                            callbackEntered.countDown()
                            check(releaseCallback.await(5, TimeUnit.SECONDS))
                        }
                    }
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
                val disposal = state.disposeAsync()
                assertTrue(transitionWaiting.await(5, TimeUnit.SECONDS))
                requireNotNull(workerThread.get()).interrupt()
                assertTrue(interruptionObserved.await(5, TimeUnit.SECONDS))
                releaseCallback.countDown()
                assertTrue(callback.get(5, TimeUnit.SECONDS))
                val failure =
                    assertFailsWith<java.util.concurrent.ExecutionException> {
                        disposal.get(5, TimeUnit.SECONDS)
                    }
                assertTrue(failure.cause is InterruptedException)
                assertEquals(listOf(41L), bridge.disposed())
            } finally {
                releaseCallback.countDown()
                executor.shutdownNow()
            }
        }

    @Test
    fun `disposal worker submission failure cleans ownership and settles future`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val rejection = java.util.concurrent.RejectedExecutionException("injected disposal rejection")
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    disposalWorkerLauncher = { throw rejection },
                    frameRenderingEnabled = false,
                )
            assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
            val result = runCatching { state.disposeAsync() }
            assertTrue(result.isSuccess)
            val completion = result.getOrThrow()
            val failure =
                assertFailsWith<java.util.concurrent.ExecutionException> {
                    completion.get(5, TimeUnit.SECONDS)
                }
            assertTrue(failure.cause === rejection)
            assertEquals(listOf(41L), bridge.disposed())
            assertTrue(state.disposeAsync() === completion)
        }

    @Test
    fun `latest callback and concurrent control complete without lock inversion`() {
        val lifecycle = LinuxPlayerLifecycle()
        val sourceGeneration = lifecycle.beginOpen()
        val playback = LinuxSourcePlaybackContext(sourceGeneration)
        playback.finishOpening(reserveAutoplay = false)
        val callbackTicket = playback.reserveCommand()
        val transitionHeld = CountDownLatch(1)
        val callbackHoldingPublicationLock = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val control =
                executor.submit<Unit> {
                    lifecycle.withSourceTransition {
                        transitionHeld.countDown()
                        check(callbackHoldingPublicationLock.await(5, TimeUnit.SECONDS))
                        playback.requestPlaying(true)
                    }
                }
            assertTrue(transitionHeld.await(5, TimeUnit.SECONDS))
            val callback =
                executor.submit<Boolean> {
                    callbackHoldingPublicationLock.countDown()
                    invokeLifecycleCallbackIfLatestCommand(
                        lifecycle,
                        sourceGeneration,
                        playback,
                        callbackTicket,
                    ) {}
                }

            control.get(2, TimeUnit.SECONDS)
            assertFalse(callback.get(2, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `reentrant callback open and concurrent open complete without lock inversion`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val concurrentWaiting = CountDownLatch(1)
            val callbackFailure = AtomicReference<Throwable?>()
            val lifecycle =
                LinuxPlayerLifecycle(transitionWaitingForCallbacksForTest = { concurrentWaiting.countDown() })
            val state = LinuxVideoPlayerState(bridge, lifecycle, frameRenderingEnabled = false)
            val executor = Executors.newFixedThreadPool(2)
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                val callback =
                    executor.submit<Boolean> {
                        state.invokeCallbackForTest {
                            callbackEntered.countDown()
                            if (!releaseCallback.await(5, TimeUnit.SECONDS)) {
                                callbackFailure.set(AssertionError("callback was not released"))
                            }
                            state.openUri("https://example.invalid/from-callback.bmp", InitialPlayerState.PAUSE)
                        }
                    }
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
                val concurrent =
                    executor.submit<Unit> {
                        state.openUri("https://example.invalid/concurrent.bmp", InitialPlayerState.PAUSE)
                    }
                assertTrue(concurrentWaiting.await(5, TimeUnit.SECONDS))
                releaseCallback.countDown()
                assertTrue(callback.get(5, TimeUnit.SECONDS))
                concurrent.get(5, TimeUnit.SECONDS)
                assertNull(callbackFailure.get())
            } finally {
                releaseCallback.countDown()
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `callback disposal future rejects a blocking wait and still completes cleanup`() =
        lifecycleTest {
            val transitionWaiting = CountDownLatch(1)
            val lifecycle =
                LinuxPlayerLifecycle(transitionWaitingForCallbacksForTest = { transitionWaiting.countDown() })
            val state = LinuxVideoPlayerState(FakeBridge(), lifecycle, frameRenderingEnabled = false)
            val disposal = AtomicReference<CompletableFuture<Unit>>()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val callback =
                    executor.submit<Boolean> {
                        state.invokeCallbackForTest {
                            val completion = state.disposeAsync()
                            disposal.set(completion)
                            check(transitionWaiting.await(5, TimeUnit.SECONDS))
                            val failure =
                                assertFailsWith<java.util.concurrent.ExecutionException> {
                                    completion.get(1, TimeUnit.SECONDS)
                                }
                            assertTrue(failure.cause is IllegalStateException)
                        }
                    }
                assertTrue(callback.get(5, TimeUnit.SECONDS))
                val actualDisposal = state.disposeAsync()
                assertFalse(actualDisposal === disposal.get())
                actualDisposal.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `callback disposal dependent future rejects a blocking wait and still completes cleanup`() =
        lifecycleTest {
            val transitionWaiting = CountDownLatch(1)
            val lifecycle =
                LinuxPlayerLifecycle(transitionWaitingForCallbacksForTest = { transitionWaiting.countDown() })
            val state = LinuxVideoPlayerState(FakeBridge(), lifecycle, frameRenderingEnabled = false)
            val dependent = AtomicReference<CompletableFuture<Unit>>()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val callback =
                    executor.submit<Boolean> {
                        state.invokeCallbackForTest {
                            val completion = state.disposeAsync().thenApply { Unit }
                            dependent.set(completion)
                            check(transitionWaiting.await(5, TimeUnit.SECONDS))
                            val failure =
                                assertFailsWith<java.util.concurrent.ExecutionException> {
                                    completion.get(1, TimeUnit.SECONDS)
                                }
                            assertTrue(failure.cause is IllegalStateException)
                        }
                    }
                assertTrue(callback.get(5, TimeUnit.SECONDS))
                assertFailsWith<java.util.concurrent.ExecutionException> {
                    dependent.get().get(5, TimeUnit.SECONDS)
                }
                deferDisposal(state)
            } finally {
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `reentrant callback open and concurrent dispose complete without lock inversion`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val disposalCompleted = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    frameRenderingEnabled = false,
                    disposalCompletedForTest = { disposalCompleted.countDown() },
                )
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val disposeStarted = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                val callbackFuture =
                    executor.submit<Boolean> {
                        state.invokeCallbackForTest {
                            callbackEntered.countDown()
                            check(releaseCallback.await(5, TimeUnit.SECONDS))
                            state.openUri(
                                "https://example.invalid/reentrant-before-dispose.bmp",
                                InitialPlayerState.PAUSE,
                            )
                        }
                    }
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
                val disposeFuture =
                    executor.submit {
                        disposeStarted.countDown()
                        state.disposeAsync().get(5, TimeUnit.SECONDS)
                    }
                assertTrue(disposeStarted.await(5, TimeUnit.SECONDS))
                releaseCallback.countDown()
                assertTrue(callbackFuture.get(5, TimeUnit.SECONDS))
                disposeFuture.get(5, TimeUnit.SECONDS)
                assertTrue(disposalCompleted.await(5, TimeUnit.SECONDS))
            } finally {
                releaseCallback.countDown()
                executor.shutdownNow()
            }
        }

    @Test
    fun `all delayed source A controls complete without touching source B`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val openedSources = LinkedBlockingQueue<Long>()
            val blockedGeneration = AtomicLong(0L)
            val expected = setOf("volume", "speed", "resize", "play", "pause", "seek", "stop")
            val startedNames = Collections.synchronizedSet(mutableSetOf<String>())
            val completedNames = Collections.synchronizedSet(mutableSetOf<String>())
            val allStarted = CountDownLatch(expected.size)
            val allCompleted = CountDownLatch(expected.size)
            val release = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceOpenCompletedForTest = { generation, failure ->
                        check(failure == null)
                        openedSources.put(generation)
                    },
                    frameRenderingEnabled = false,
                    beforeAsyncOperationForTest = { name, generation ->
                        if (generation == blockedGeneration.get() && name in expected && startedNames.add(name)) {
                            allStarted.countDown()
                            check(release.await(15, TimeUnit.SECONDS)) { "timed out releasing $name" }
                        }
                    },
                    asyncOperationCompletedForTest = { name, generation ->
                        if (generation == blockedGeneration.get() && name in expected && completedNames.add(name)) {
                            allCompleted.countDown()
                        }
                    },
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/source-a.bmp", InitialPlayerState.PAUSE)
                val sourceA = openedSources.poll(5, TimeUnit.SECONDS)
                assertTrue(sourceA != null)
                blockedGeneration.set(sourceA)

                state.volume = 0.4f
                state.playbackSpeed = 1.5f
                state.onResized(800, 600)
                state.play()
                state.pause()
                state.seekTo(500f)
                state.stop()
                assertTrue(
                    allStarted.await(5, TimeUnit.SECONDS),
                    "not all A controls reached their barrier: $startedNames",
                )

                state.openUri("https://example.invalid/source-b.bmp", InitialPlayerState.PAUSE)
                val sourceB = openedSources.poll(5, TimeUnit.SECONDS)
                assertTrue(sourceB != null && sourceB != sourceA)
                val baselineCallCount = bridge.calls().size

                release.countDown()
                assertTrue(
                    allCompleted.await(5, TimeUnit.SECONDS),
                    "not all A controls completed: $completedNames",
                )
                assertEquals(expected, completedNames.toSet())
                val postBaselineBCalls = bridge.calls().drop(baselineCallCount).filter { it.handle == 123L }
                assertTrue(
                    postBaselineBCalls.all { it.name == "consumeEnd" },
                    "stale A control touched B: $postBaselineBCalls",
                )
            } finally {
                release.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `stale source metadata is rejected after replacement generation advances`() =
        lifecycleTest {
            val bridge = FakeBridge(blockTitleHandle = 82L)
            val readySources = LinkedBlockingQueue<Long>()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { generation, _ -> readySources.put(generation) },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/source-a.bmp", InitialPlayerState.PAUSE)
                assertTrue(bridge.titleEntered.await(5, TimeUnit.SECONDS))

                state.openUri("https://example.invalid/source-b.bmp", InitialPlayerState.PAUSE)
                bridge.releaseTitle.countDown()
                assertTrue(readySources.poll(5, TimeUnit.SECONDS) != null)
                assertEquals("title-123", state.metadata.title)
                assertEquals(2, state.metadata.width)
                assertEquals(2, state.metadata.height)
            } finally {
                bridge.releaseTitle.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `stale position and EOS update cannot publish into replacement source`() =
        lifecycleTest {
            val bridge = FakeBridge(blockCurrentTimeHandle = 82L)
            val readySources = LinkedBlockingQueue<Long>()
            val endedCallbacks = AtomicInteger()
            val restartCallbacks = AtomicInteger()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { generation, _ -> readySources.put(generation) },
                    frameRenderingEnabled = false,
                )
            val executor = Executors.newSingleThreadExecutor()
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.onPlaybackEnded = { endedCallbacks.incrementAndGet() }
                state.onRestart = { restartCallbacks.incrementAndGet() }
                state.openUri("https://example.invalid/source-a.bmp", InitialPlayerState.PAUSE)
                val sourceA = readySources.poll(5, TimeUnit.SECONDS)
                assertTrue(sourceA != null)

                val staleUpdate = executor.submit { runBlocking { state.updatePositionForTest(sourceA) } }
                assertTrue(bridge.currentTimeEntered.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/source-b.bmp", InitialPlayerState.PAUSE)
                bridge.releaseCurrentTime.countDown()
                staleUpdate.get(5, TimeUnit.SECONDS)
                val sourceB = checkNotNull(readySources.poll(5, TimeUnit.SECONDS))
                state.updatePositionForTest(sourceB)
                assertEquals("00:07", state.positionText)
                assertEquals("00:10", state.durationText)
                assertEquals(700f, state.sliderPos)
                assertFalse(state.isLoading)
                assertEquals(0, endedCallbacks.get())
                assertEquals(0, restartCallbacks.get())
            } finally {
                bridge.releaseCurrentTime.countDown()
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `production state fails closed and destroys player on generation exhaustion`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val lifecycle =
                LinuxPlayerLifecycle(generations = LinuxPlayerGeneration(initialGeneration = Long.MAX_VALUE - 1))
            val exhausted = CountDownLatch(1)
            val exhaustionFailure = AtomicReference<Throwable?>()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    lifecycle,
                    sourceOpenCompletedForTest = { generation, failure ->
                        if (generation == 0L) {
                            exhaustionFailure.set(failure)
                            exhausted.countDown()
                        }
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/exhausted.bmp", InitialPlayerState.PAUSE)
                assertTrue(exhausted.await(5, TimeUnit.SECONDS))
                assertNull(exhaustionFailure.get())
                assertEquals(1, bridge.disposed().count { it == 41L })
                assertEquals(1, bridge.createCount.get())
                assertTrue(bridge.opened().isEmpty())
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `generation exhaustion clears active managed state and frames while retiring player`() =
        lifecycleTest {
            val bridge = FakeBridge(copyFrames = true)
            val lifecycle =
                LinuxPlayerLifecycle(generations = LinuxPlayerGeneration(initialGeneration = Long.MAX_VALUE - 2))
            val sourceReady = CountDownLatch(1)
            val framePublished = CountDownLatch(1)
            val exhausted = CountDownLatch(1)
            val stateRef = AtomicReference<LinuxVideoPlayerState?>()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    lifecycle,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    sourceOpenCompletedForTest = { generation, _ ->
                        if (generation == 0L) exhausted.countDown()
                    },
                    frameDeliveryObserver = { _, published ->
                        if (published && stateRef.get()?.currentFrameState?.value != null) framePublished.countDown()
                    },
                    frameRenderingEnabled = true,
                )
            stateRef.set(state)
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/active-before-exhaustion.bmp", InitialPlayerState.PLAY)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertTrue(framePublished.await(5, TimeUnit.SECONDS))
                assertTrue(state.hasMedia)
                assertTrue(state.isPlaying)
                assertNotNull(state.currentFrameState.value)
                state.userDragging = true
                state.sliderPos = 700f

                state.openUri("https://example.invalid/exhausted-replacement.bmp", InitialPlayerState.PAUSE)
                assertTrue(exhausted.await(5, TimeUnit.SECONDS))

                assertEquals(1, bridge.disposed().count { it == 41L })
                assertFalse(state.hasMedia)
                assertFalse(state.isPlaying)
                assertFalse(state.isLoading)
                assertFalse(state.userDragging)
                assertEquals(0f, state.sliderPos)
                assertEquals("00:00", state.positionText)
                assertEquals("00:00", state.durationText)
                assertNull(state.currentFrameState.value)
                assertEquals(0L, state.queuedFrameGenerationForTest())
            } finally {
                deferDisposal(state)
            }
        }

    @Test
    fun `stale source A reservation cannot install over source B jobs`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val firstFrameReservation = AtomicBoolean(true)
            val sourceAReserved = CountDownLatch(1)
            val releaseSourceA = CountDownLatch(1)
            val sourceBReady = CountDownLatch(1)
            val workerFailure = AtomicReference<Throwable?>()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceBReady.countDown() },
                    jobTransitionObserverForTest = { slot, phase, _ ->
                        if (slot == "frame" && phase == "reserved" &&
                            firstFrameReservation.compareAndSet(true, false)
                        ) {
                            sourceAReserved.countDown()
                            if (!releaseSourceA.await(5, TimeUnit.SECONDS)) {
                                workerFailure.set(AssertionError("source A reservation was not released"))
                            }
                        }
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.test/job-a.mp4", InitialPlayerState.PAUSE)
                assertTrue(sourceAReserved.await(5, TimeUnit.SECONDS))

                state.openUri("https://example.test/job-b.mp4", InitialPlayerState.PAUSE)
                releaseSourceA.countDown()
                assertTrue(sourceBReady.await(5, TimeUnit.SECONDS))
                val sourceB = state.captureSourceGenerationForTest()
                assertEquals(sourceB, state.frameJobTicketForTest()?.sourceGeneration)
                assertEquals(sourceB, state.bufferingJobTicketForTest()?.sourceGeneration)
                assertNull(workerFailure.get())
            } finally {
                releaseSourceA.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `stale source A finalizer cannot clear source B frame job`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val sourceReady = LinkedBlockingQueue<Long>()
            val sourceAFinalizing = CountDownLatch(1)
            val releaseSourceAFinalizer = CountDownLatch(1)
            val sourceAFinalized = CountDownLatch(1)
            val sourceAGeneration = AtomicLong(0L)
            val workerFailure = AtomicReference<Throwable?>()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { generation, _ -> sourceReady.offer(generation) },
                    jobTransitionObserverForTest = { slot, phase, ticket ->
                        if (slot == "frame" && ticket.sourceGeneration == sourceAGeneration.get()) {
                            if (phase == "finalizing") {
                                sourceAFinalizing.countDown()
                                if (!releaseSourceAFinalizer.await(5, TimeUnit.SECONDS)) {
                                    workerFailure.set(AssertionError("source A finalizer was not released"))
                                }
                            }
                            if (phase == "finalized") sourceAFinalized.countDown()
                        }
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.test/finalizer-a.mp4", InitialPlayerState.PAUSE)
                sourceAGeneration.set(checkNotNull(sourceReady.poll(5, TimeUnit.SECONDS)))

                state.openUri("https://example.test/finalizer-b.mp4", InitialPlayerState.PAUSE)
                assertTrue(sourceAFinalizing.await(5, TimeUnit.SECONDS))
                val sourceB = checkNotNull(sourceReady.poll(5, TimeUnit.SECONDS))
                val sourceBTicket = checkNotNull(state.frameJobTicketForTest())
                assertEquals(sourceB, sourceBTicket.sourceGeneration)

                releaseSourceAFinalizer.countDown()
                assertTrue(sourceAFinalized.await(5, TimeUnit.SECONDS))
                assertEquals(sourceBTicket, state.frameJobTicketForTest())
                assertNull(workerFailure.get())
            } finally {
                releaseSourceAFinalizer.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `delayed pause cannot cancel newer same generation jobs`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val sourceReady = CountDownLatch(1)
            val pauseStarted = CountDownLatch(1)
            val releasePause = CountDownLatch(1)
            val pauseCompleted = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    beforeAsyncOperationForTest = { name, _ ->
                        if (name == "pause") {
                            pauseStarted.countDown()
                            check(releasePause.await(5, TimeUnit.SECONDS))
                        }
                    },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "pause") pauseCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.test/pause-ticket.mp4", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                val source = state.captureSourceGenerationForTest()

                state.pause()
                assertTrue(pauseStarted.await(5, TimeUnit.SECONDS))
                state.restartJobsForTest(source)
                val newerFrame = checkNotNull(state.frameJobTicketForTest())
                val newerBuffering = checkNotNull(state.bufferingJobTicketForTest())

                releasePause.countDown()
                assertTrue(pauseCompleted.await(5, TimeUnit.SECONDS))
                assertEquals(newerFrame, state.frameJobTicketForTest())
                assertEquals(newerBuffering, state.bufferingJobTicketForTest())
            } finally {
                releasePause.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `delayed stop cannot cancel newer same generation jobs`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val sourceReady = CountDownLatch(1)
            val stopStarted = CountDownLatch(1)
            val releaseStop = CountDownLatch(1)
            val stopCompleted = CountDownLatch(1)
            val workerFailure = AtomicReference<Throwable?>()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    beforeAsyncOperationForTest = { name, _ ->
                        if (name == "stop") {
                            stopStarted.countDown()
                            if (!releaseStop.await(5, TimeUnit.SECONDS)) {
                                workerFailure.set(AssertionError("stop was not released"))
                            }
                        }
                    },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "stop") stopCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.test/stop-ticket.mp4", InitialPlayerState.PLAY)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                val source = state.captureSourceGenerationForTest()

                state.stop()
                assertTrue(stopStarted.await(5, TimeUnit.SECONDS))
                state.restartJobsForTest(source)
                val newerFrame = checkNotNull(state.frameJobTicketForTest())
                val newerBuffering = checkNotNull(state.bufferingJobTicketForTest())

                releaseStop.countDown()
                assertTrue(stopCompleted.await(5, TimeUnit.SECONDS))
                assertNull(workerFailure.get())
                assertEquals(newerFrame, state.frameJobTicketForTest())
                assertEquals(newerBuffering, state.bufferingJobTicketForTest())
            } finally {
                releaseStop.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `play during source transition is applied to the newly published context`() =
        lifecycleTest {
            val generationAdvanced = CountDownLatch(1)
            val controlWaitingForTransition = CountDownLatch(1)
            val releaseTransition = CountDownLatch(1)
            val advanceCount = AtomicInteger()
            val lifecycle =
                LinuxPlayerLifecycle(
                    afterGenerationAdvanced = {
                        if (advanceCount.incrementAndGet() == 2) {
                            generationAdvanced.countDown()
                            check(releaseTransition.await(5, TimeUnit.SECONDS))
                        }
                    },
                    transitionWaitingForCallbacksForTest = { controlWaitingForTransition.countDown() },
                )
            val sourceReady = CountDownLatch(1)
            val readyDesiredPlaying = AtomicReference<Boolean?>()
            val state =
                LinuxVideoPlayerState(
                    FakeBridge(),
                    lifecycle,
                    sourceReadyObserver = { _, desiredPlaying ->
                        readyDesiredPlaying.set(desiredPlaying)
                        sourceReady.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            val executor = Executors.newFixedThreadPool(2)
            try {
                val opening =
                    executor.submit {
                        state.openUri("https://example.invalid/transition-play.mp4", InitialPlayerState.PAUSE)
                    }
                assertTrue(generationAdvanced.await(5, TimeUnit.SECONDS))

                val playing = executor.submit { state.play() }
                assertTrue(controlWaitingForTransition.await(5, TimeUnit.SECONDS))
                releaseTransition.countDown()
                opening.get(5, TimeUnit.SECONDS)
                playing.get(5, TimeUnit.SECONDS)

                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertEquals(true, readyDesiredPlaying.get())
                assertTrue(state.isPlaying)
            } finally {
                releaseTransition.countDown()
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `pause during source transition is applied to the newly published context`() =
        lifecycleTest {
            val generationAdvanced = CountDownLatch(1)
            val controlWaitingForTransition = CountDownLatch(1)
            val releaseTransition = CountDownLatch(1)
            val advanceCount = AtomicInteger()
            val lifecycle =
                LinuxPlayerLifecycle(
                    afterGenerationAdvanced = {
                        if (advanceCount.incrementAndGet() == 2) {
                            generationAdvanced.countDown()
                            check(releaseTransition.await(5, TimeUnit.SECONDS))
                        }
                    },
                    transitionWaitingForCallbacksForTest = { controlWaitingForTransition.countDown() },
                )
            val sourceReady = CountDownLatch(1)
            val readyDesiredPlaying = AtomicReference<Boolean?>()
            val state =
                LinuxVideoPlayerState(
                    FakeBridge(),
                    lifecycle,
                    sourceReadyObserver = { _, desiredPlaying ->
                        readyDesiredPlaying.set(desiredPlaying)
                        sourceReady.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            val executor = Executors.newFixedThreadPool(2)
            try {
                val opening =
                    executor.submit {
                        state.openUri("https://example.invalid/transition-pause.mp4", InitialPlayerState.PLAY)
                    }
                assertTrue(generationAdvanced.await(5, TimeUnit.SECONDS))

                val pausing = executor.submit { state.pause() }
                assertTrue(controlWaitingForTransition.await(5, TimeUnit.SECONDS))
                releaseTransition.countDown()
                opening.get(5, TimeUnit.SECONDS)
                pausing.get(5, TimeUnit.SECONDS)

                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertEquals(false, readyDesiredPlaying.get())
                assertFalse(state.isPlaying)
            } finally {
                releaseTransition.countDown()
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `stop during source transition is applied to the newly published context`() =
        lifecycleTest {
            val generationAdvanced = CountDownLatch(1)
            val controlWaitingForTransition = CountDownLatch(1)
            val releaseTransition = CountDownLatch(1)
            val advanceCount = AtomicInteger()
            val lifecycle =
                LinuxPlayerLifecycle(
                    afterGenerationAdvanced = {
                        if (advanceCount.incrementAndGet() == 2) {
                            generationAdvanced.countDown()
                            check(releaseTransition.await(5, TimeUnit.SECONDS))
                        }
                    },
                    transitionWaitingForCallbacksForTest = { controlWaitingForTransition.countDown() },
                )
            val sourceReady = CountDownLatch(1)
            val readyDesiredPlaying = AtomicReference<Boolean?>()
            val state =
                LinuxVideoPlayerState(
                    FakeBridge(),
                    lifecycle,
                    sourceReadyObserver = { _, desiredPlaying ->
                        readyDesiredPlaying.set(desiredPlaying)
                        sourceReady.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            val executor = Executors.newFixedThreadPool(2)
            try {
                val opening =
                    executor.submit {
                        state.openUri("https://example.invalid/transition-stop.mp4", InitialPlayerState.PLAY)
                    }
                assertTrue(generationAdvanced.await(5, TimeUnit.SECONDS))

                val stopping = executor.submit { state.stop() }
                assertTrue(controlWaitingForTransition.await(5, TimeUnit.SECONDS))
                releaseTransition.countDown()
                opening.get(5, TimeUnit.SECONDS)
                stopping.get(5, TimeUnit.SECONDS)

                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertEquals(false, readyDesiredPlaying.get())
                assertFalse(state.isPlaying)
            } finally {
                releaseTransition.countDown()
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `seek during source transition is applied to the newly published context`() =
        lifecycleTest {
            val generationAdvanced = CountDownLatch(1)
            val controlWaitingForTransition = CountDownLatch(1)
            val releaseTransition = CountDownLatch(1)
            val advanceCount = AtomicInteger()
            val lifecycle =
                LinuxPlayerLifecycle(
                    afterGenerationAdvanced = {
                        if (advanceCount.incrementAndGet() == 2) {
                            generationAdvanced.countDown()
                            check(releaseTransition.await(5, TimeUnit.SECONDS))
                        }
                    },
                    transitionWaitingForCallbacksForTest = { controlWaitingForTransition.countDown() },
                )
            val sourceReady = CountDownLatch(1)
            val seekCompleted = CountDownLatch(1)
            val bridge = FakeBridge()
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    lifecycle,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    asyncOperationCompletedForTest = { operation, _ ->
                        if (operation == "seek") seekCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            val executor = Executors.newFixedThreadPool(2)
            try {
                val opening =
                    executor.submit {
                        state.openUri("https://example.invalid/transition-seek.mp4", InitialPlayerState.PAUSE)
                    }
                assertTrue(generationAdvanced.await(5, TimeUnit.SECONDS))

                val seeking = executor.submit { state.seekTo(500f) }
                assertTrue(controlWaitingForTransition.await(5, TimeUnit.SECONDS))
                releaseTransition.countDown()
                opening.get(5, TimeUnit.SECONDS)
                seeking.get(5, TimeUnit.SECONDS)

                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertTrue(seekCompleted.await(5, TimeUnit.SECONDS))
                assertTrue(bridge.calls().any { it.name == "seek" && it.handle == 82L })
            } finally {
                releaseTransition.countDown()
                executor.shutdownNow()
                deferDisposal(state)
            }
        }

    @Test
    fun `pause after opening snapshot prevents stale initial autoplay`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val beforeIntentApply = CountDownLatch(1)
            val releaseIntentApply = CountDownLatch(1)
            val sourceReady = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    beforeOpeningIntentApplyForTest = { _, shouldPlay ->
                        if (shouldPlay) {
                            beforeIntentApply.countDown()
                            check(releaseIntentApply.await(5, TimeUnit.SECONDS))
                        }
                    },
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.test/pause-autoplay.mp4", InitialPlayerState.PLAY)
                assertTrue(beforeIntentApply.await(5, TimeUnit.SECONDS))

                state.pause()
                assertFalse(state.desiredPlayingForTest())
                assertFalse(bridge.calls().any { it.name == "pause" })

                releaseIntentApply.countDown()
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertFalse(bridge.calls().any { it.name == "play" && it.handle == 82L })
                assertFalse(state.isPlaying)
            } finally {
                releaseIntentApply.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `replacement destruction failure aborts opening and poisons later opens`() =
        lifecycleTest {
            val bridge = FakeBridge(failDisposeHandle = 82L)
            val openCompleted = LinkedBlockingQueue<Pair<Long, Throwable?>>()
            val sourceReady = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    sourceOpenCompletedForTest = { generation, failure -> openCompleted.put(generation to failure) },
                    frameRenderingEnabled = false,
                )
            var disposalAttempted = false
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/a.mp4", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                assertTrue(openCompleted.poll(5, TimeUnit.SECONDS)?.second == null)
                val opensAfterA = bridge.calls().count { it.name == "open" }
                state.openUri("https://example.invalid/b.mp4", InitialPlayerState.PAUSE)
                val failedOpen = openCompleted.poll(5, TimeUnit.SECONDS)
                assertTrue(failedOpen?.second is IllegalStateException)
                val createsAfterFailure = bridge.createCount.get()
                state.openUri("https://example.invalid/c.mp4", InitialPlayerState.PAUSE)
                assertEquals(createsAfterFailure, bridge.createCount.get())
                assertEquals(opensAfterA, bridge.calls().count { it.name == "open" })
                val disposalFailure =
                    assertFailsWith<java.util.concurrent.ExecutionException> {
                        disposalAttempted = true
                        state.disposeAsync().get(5, TimeUnit.SECONDS)
                    }
                assertTrue(disposalFailure.cause is IllegalStateException)
            } finally {
                if (!disposalAttempted) deferDisposal(state)
            }
        }

    @Test
    fun `disposeAsync is a production completion boundary`() =
        lifecycleTest {
            val bridge = FakeBridge()
            val sourceReady = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    frameRenderingEnabled = false,
                )
            state.openUri("https://example.invalid/a.mp4", InitialPlayerState.PAUSE)
            assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
            val generation = state.captureSourceGenerationForTest()
            val leaseWorker = Executors.newSingleThreadExecutor()
            val leasedHandle = AtomicLong()
            val leaseEntered = CountDownLatch(1)
            val releaseLease = CountDownLatch(1)
            val disposedBefore = bridge.disposed().size
            try {
                val lease =
                    leaseWorker.submit {
                        state.withPlayerForTest(generation) {
                            leasedHandle.set(it)
                            leaseEntered.countDown()
                            check(releaseLease.await(5, TimeUnit.SECONDS))
                        }
                    }
                assertTrue(leaseEntered.await(5, TimeUnit.SECONDS))
                val completion = state.disposeAsync()
                releaseLease.countDown()
                lease.get(5, TimeUnit.SECONDS)
                completion.get(5, TimeUnit.SECONDS)
                assertEquals(listOf(leasedHandle.get()), bridge.disposed().drop(disposedBefore))
            } finally {
                releaseLease.countDown()
                leaseWorker.shutdownNow()
            }
        }

    @Test
    fun `newer volume waits for older JNI completion and is applied last`() =
        lifecycleTest {
            val bridge = FakeBridge(blockVolumeValue = 0.25f)
            val twoUserVolumeOperationsEntered = CountDownLatch(2)
            val twoUserVolumeOperationsCompleted = CountDownLatch(2)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    beforeAsyncOperationForTest = { name, _ ->
                        if (name == "volume") twoUserVolumeOperationsEntered.countDown()
                    },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "volume") twoUserVolumeOperationsCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))

                state.volume = 0.25f
                assertTrue(bridge.blockedVolumeEntered.await(5, TimeUnit.SECONDS))
                state.volume = 0.75f
                assertTrue(twoUserVolumeOperationsEntered.await(5, TimeUnit.SECONDS))

                assertEquals(listOf(1.0f, 0.25f), bridge.volumeValues())
                bridge.releaseBlockedVolume.countDown()
                assertTrue(twoUserVolumeOperationsCompleted.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(1.0f, 0.25f, 0.75f), bridge.volumeValues())
            } finally {
                bridge.releaseBlockedVolume.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `newer speed waits for older JNI completion and is applied last`() =
        lifecycleTest {
            val bridge = FakeBridge(blockSpeedValue = 0.75f)
            val twoUserSpeedOperationsEntered = CountDownLatch(2)
            val twoUserSpeedOperationsCompleted = CountDownLatch(2)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    beforeAsyncOperationForTest = { name, _ ->
                        if (name == "speed") twoUserSpeedOperationsEntered.countDown()
                    },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "speed") twoUserSpeedOperationsCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialSpeedApplied.await(5, TimeUnit.SECONDS))

                state.playbackSpeed = 0.75f
                assertTrue(bridge.blockedSpeedEntered.await(5, TimeUnit.SECONDS))
                state.playbackSpeed = 1.5f
                assertTrue(twoUserSpeedOperationsEntered.await(5, TimeUnit.SECONDS))

                assertEquals(listOf(1.0f, 0.75f), bridge.speedValues())
                bridge.releaseBlockedSpeed.countDown()
                assertTrue(twoUserSpeedOperationsCompleted.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(1.0f, 0.75f, 1.5f), bridge.speedValues())
            } finally {
                bridge.releaseBlockedSpeed.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `newer resize waits for older JNI completion and is applied last`() =
        lifecycleTest {
            val bridge = FakeBridge(blockOutputSize = 400 to 225)
            val twoResizeRequestsReadyToApply = CountDownLatch(2)
            val twoResizeOperationsCompleted = CountDownLatch(2)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    beforeResizeApplyForTest = { _, _ -> twoResizeRequestsReadyToApply.countDown() },
                    asyncOperationCompletedForTest = { name, _ ->
                        if (name == "resize") twoResizeOperationsCompleted.countDown()
                    },
                    frameRenderingEnabled = false,
                )
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))

                state.onResized(400, 400)
                assertTrue(bridge.blockedOutputSizeEntered.await(5, TimeUnit.SECONDS))
                state.onResized(800, 400)
                assertTrue(twoResizeRequestsReadyToApply.await(5, TimeUnit.SECONDS))

                bridge.releaseBlockedOutputSize.countDown()
                assertTrue(twoResizeOperationsCompleted.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(400 to 225, 711 to 400), bridge.outputSizes())
            } finally {
                bridge.releaseBlockedOutputSize.countDown()
                deferDisposal(state)
            }
        }

    @Test
    fun `dispose waits for active JNI lease and destroys the handle exactly once`() =
        lifecycleTest {
            val bridge = FakeBridge(blockPause = true)
            val sourceReady = CountDownLatch(1)
            val state =
                LinuxVideoPlayerState(
                    bridge,
                    sourceReadyObserver = { _, _ -> sourceReady.countDown() },
                    frameRenderingEnabled = false,
                )
            var disposed = false
            try {
                assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
                state.openUri("https://example.invalid/source.bmp", InitialPlayerState.PAUSE)
                assertTrue(sourceReady.await(5, TimeUnit.SECONDS))
                state.pause()
                assertTrue(bridge.pauseEntered.await(5, TimeUnit.SECONDS))
                val completion = state.disposeAsync()
                disposed = true
                assertFalse(bridge.disposed().contains(82L))
                bridge.releasePause.countDown()
                completion.get(5, TimeUnit.SECONDS)
                assertEquals(1, bridge.disposed().count { it == 82L })
            } finally {
                bridge.releasePause.countDown()
                if (!disposed) deferDisposal(state)
            }
        }

    @Test
    fun `production disposal failure is observable after every retirement is attempted`() =
        lifecycleTest {
            val bridge = FakeBridge(failDisposeHandle = 41L)
            val state = LinuxVideoPlayerState(bridge, frameRenderingEnabled = false)
            assertTrue(bridge.initialVolumeApplied.await(5, TimeUnit.SECONDS))
            val failure =
                try {
                    state.disposeAsync().get(5, TimeUnit.SECONDS)
                    null
                } catch (e: Exception) {
                    e.cause
                }
            assertTrue(failure is IllegalStateException)
            assertEquals(listOf(41L), bridge.disposed())
        }

    private data class PlayerCall(
        val name: String,
        val handle: Long,
    )

    private class FakeBridge(
        private val blockFirstCreate: Boolean = false,
        private val blockSecondCreate: Boolean = false,
        private val blockThirdCreate: Boolean = false,
        private val failFirstCreate: Boolean = false,
        private val blockPause: Boolean = false,
        private val blockSeek: Boolean = false,
        private val blockOpenHandle: Long? = null,
        private val blockDisposeHandle: Long? = null,
        private val failDisposeHandle: Long? = null,
        private val failEveryDispose: Boolean = false,
        private val blockTitleHandle: Long? = null,
        private val blockCurrentTimeHandle: Long? = null,
        private val blockEveryCurrentTime: Boolean = false,
        private val blockVolumeValue: Float? = null,
        private val blockSpeedValue: Float? = null,
        private val blockOutputSize: Pair<Int, Int>? = null,
        private val onPlay: (() -> Unit)? = null,
        private val onSeek: (() -> Unit)? = null,
        private val failPlay: AtomicBoolean? = null,
        private val failPause: AtomicBoolean? = null,
        private val consumeEnd: Boolean = false,
        private val blockConsumeEnd: Boolean = false,
        private val blockConsumeEndFlag: AtomicBoolean? = null,
        private val duration: Double = 10.0,
        private val durationValue: AtomicReference<Double>? = null,
        private val copyFrames: Boolean = false,
        private val trackDurationBeforeOpenComplete: Boolean = false,
    ) : LinuxPlayerBridge {
        val createCount = AtomicInteger()
        private val pendingEnd = AtomicBoolean(consumeEnd)
        val firstCreateEntered = CountDownLatch(1)
        val releaseFirstCreate = CountDownLatch(1)
        val firstCreateFinished = CountDownLatch(1)
        val secondCreateEntered = CountDownLatch(1)
        val releaseSecondCreate = CountDownLatch(1)
        val thirdCreateEntered = CountDownLatch(1)
        val releaseThirdCreate = CountDownLatch(1)
        val thirdCreateFinished = CountDownLatch(1)
        val blockedDisposeEntered = CountDownLatch(1)
        val releaseBlockedDispose = CountDownLatch(1)
        val blockedOpenEntered = CountDownLatch(1)
        val releaseBlockedOpen = CountDownLatch(1)
        val pauseEntered = CountDownLatch(1)
        val releasePause = CountDownLatch(1)
        val seekEntered = CountDownLatch(1)
        val releaseSeek = CountDownLatch(1)
        val titleEntered = CountDownLatch(1)
        val releaseTitle = CountDownLatch(1)
        val currentTimeEntered = CountDownLatch(1)
        val releaseCurrentTime = CountDownLatch(1)
        val initialVolumeApplied = CountDownLatch(1)
        val blockedVolumeEntered = CountDownLatch(1)
        val releaseBlockedVolume = CountDownLatch(1)
        val initialSpeedApplied = CountDownLatch(1)
        val blockedSpeedEntered = CountDownLatch(1)
        val releaseBlockedSpeed = CountDownLatch(1)
        val blockedOutputSizeEntered = CountDownLatch(1)
        val releaseBlockedOutputSize = CountDownLatch(1)
        val consumeEndEntered = CountDownLatch(1)
        val releaseConsumeEnd = CountDownLatch(1)
        private val openCompleted = AtomicBoolean()
        val durationBeforeOpenComplete = AtomicBoolean()
        private val disposedHandles: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        private val openedHandles: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        private val playerCalls: MutableList<PlayerCall> = Collections.synchronizedList(mutableListOf())
        private val appliedVolumeValues: MutableList<Float> = Collections.synchronizedList(mutableListOf())
        private val appliedSpeedValues: MutableList<Float> = Collections.synchronizedList(mutableListOf())
        private val appliedOutputSizes: MutableList<Pair<Int, Int>> = Collections.synchronizedList(mutableListOf())

        fun signalEndForTest() {
            pendingEnd.set(true)
        }

        override fun createPlayer(): Long =
            when (createCount.incrementAndGet()) {
                1 -> {
                    try {
                        if (blockFirstCreate) {
                            firstCreateEntered.countDown()
                            check(releaseFirstCreate.await(5, TimeUnit.SECONDS))
                        }
                        if (failFirstCreate) error("delayed initialization failure")
                        41L
                    } finally {
                        firstCreateFinished.countDown()
                    }
                }

                2 -> {
                    if (blockSecondCreate) {
                        secondCreateEntered.countDown()
                        check(releaseSecondCreate.await(5, TimeUnit.SECONDS))
                    }
                    82L
                }

                3 -> {
                    try {
                        if (blockThirdCreate) {
                            thirdCreateEntered.countDown()
                            check(releaseThirdCreate.await(5, TimeUnit.SECONDS))
                        }
                        123L
                    } finally {
                        thirdCreateFinished.countDown()
                    }
                }

                else -> {
                    164L
                }
            }

        override fun openUri(
            handle: Long,
            uri: String,
        ) {
            openedHandles += handle
            record("open", handle)
            if (handle == blockOpenHandle) {
                blockedOpenEntered.countDown()
                check(releaseBlockedOpen.await(5, TimeUnit.SECONDS))
            }
            openCompleted.set(true)
        }

        override fun play(handle: Long) {
            record("play", handle)
            if (failPlay?.getAndSet(false) == true) error("play failed")
            onPlay?.invoke()
        }

        override fun pause(handle: Long) {
            record("pause", handle)
            if (failPause?.getAndSet(false) == true) error("pause failed")
            if (blockPause) {
                pauseEntered.countDown()
                check(releasePause.await(5, TimeUnit.SECONDS))
            }
        }

        override fun setVolume(
            handle: Long,
            volume: Float,
        ) {
            record("volume", handle)
            appliedVolumeValues += volume
            initialVolumeApplied.countDown()
            if (volume == blockVolumeValue) {
                blockedVolumeEntered.countDown()
                check(releaseBlockedVolume.await(5, TimeUnit.SECONDS))
            }
        }

        override fun seekTo(
            handle: Long,
            time: Double,
        ) {
            record("seek", handle)
            onSeek?.invoke()
            if (blockSeek) {
                seekEntered.countDown()
                check(releaseSeek.await(5, TimeUnit.SECONDS))
            }
        }

        override fun disposePlayer(handle: Long) {
            disposedHandles += handle
            if (handle == blockDisposeHandle) {
                blockedDisposeEntered.countDown()
                check(releaseBlockedDispose.await(5, TimeUnit.SECONDS))
            }
            if (failEveryDispose ||
                handle == failDisposeHandle
            ) {
                throw IllegalStateException("dispose failed for $handle")
            }
        }

        override fun setPlaybackSpeed(
            handle: Long,
            speed: Float,
        ) {
            record("speed", handle)
            appliedSpeedValues += speed
            initialSpeedApplied.countDown()
            if (speed == blockSpeedValue) {
                blockedSpeedEntered.countDown()
                check(releaseBlockedSpeed.await(5, TimeUnit.SECONDS))
            }
        }

        override fun copyLatestFrame(
            handle: Long,
            destination: ByteBuffer,
            expectedWidth: Int,
            expectedHeight: Int,
            destinationStride: Int,
            outInfo: IntArray,
        ): Int {
            if (!copyFrames) return LinuxNativeBridge.FRAME_COPY_NOT_READY
            repeat(expectedHeight) { row ->
                repeat(expectedWidth) { column ->
                    val offset = row * destinationStride + column * 4
                    destination.put(offset, 0x11)
                    destination.put(offset + 1, 0x22)
                    destination.put(offset + 2, 0x33)
                    destination.put(offset + 3, 0x7f)
                }
            }
            outInfo[0] = expectedWidth
            outInfo[1] = expectedHeight
            outInfo[2] = expectedWidth * 4
            return LinuxNativeBridge.FRAME_COPY_OK
        }

        override fun wrapPointer(
            address: Long,
            size: Long,
        ): ByteBuffer? = ByteBuffer.allocateDirect(size.toInt())

        override fun frameWidth(handle: Long) = 2

        override fun frameHeight(handle: Long) = 2

        override fun setOutputSize(
            handle: Long,
            width: Int,
            height: Int,
        ): Int {
            record("resize", handle)
            val outputSize = width to height
            if (outputSize == blockOutputSize) {
                blockedOutputSizeEntered.countDown()
                check(releaseBlockedOutputSize.await(5, TimeUnit.SECONDS))
            }
            appliedOutputSizes += outputSize
            return 0
        }

        override fun videoDuration(handle: Long): Double {
            if (trackDurationBeforeOpenComplete && !openCompleted.get()) {
                durationBeforeOpenComplete.set(true)
            }
            return durationValue?.get() ?: duration
        }

        override fun currentTime(handle: Long): Double {
            if (blockEveryCurrentTime || handle == blockCurrentTimeHandle) {
                currentTimeEntered.countDown()
                check(releaseCurrentTime.await(5, TimeUnit.SECONDS))
            }
            return if (handle == 123L) 7.0 else 3.0
        }

        override fun videoTitle(handle: Long): String {
            if (handle == blockTitleHandle) {
                titleEntered.countDown()
                check(releaseTitle.await(5, TimeUnit.SECONDS))
            }
            return "title-$handle"
        }

        override fun videoBitrate(handle: Long) = 0L

        override fun videoMimeType(handle: Long): String? = null

        override fun audioChannels(handle: Long) = 2

        override fun audioSampleRate(handle: Long) = 48_000

        override fun frameRate(handle: Long) = 30f

        override fun consumeDidPlayToEnd(handle: Long): Boolean {
            record("consumeEnd", handle)
            if (blockConsumeEnd || blockConsumeEndFlag?.get() == true) {
                consumeEndEntered.countDown()
                check(releaseConsumeEnd.await(5, TimeUnit.SECONDS))
            }
            return pendingEnd.getAndSet(false)
        }

        private fun record(
            name: String,
            handle: Long,
        ) {
            playerCalls += PlayerCall(name, handle)
        }

        fun calls(): List<PlayerCall> = synchronized(playerCalls) { playerCalls.toList() }

        fun opened(): List<Long> = synchronized(openedHandles) { openedHandles.toList() }

        fun volumeValues(): List<Float> = synchronized(appliedVolumeValues) { appliedVolumeValues.toList() }

        fun speedValues(): List<Float> = synchronized(appliedSpeedValues) { appliedSpeedValues.toList() }

        fun outputSizes(): List<Pair<Int, Int>> = synchronized(appliedOutputSizes) { appliedOutputSizes.toList() }

        fun disposed(): List<Long> = synchronized(disposedHandles) { disposedHandles.toList() }
    }
}
