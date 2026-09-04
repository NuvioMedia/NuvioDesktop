package com.nuvio.app.features.player.desktop

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val failNativeCreate: NativePlayerCreate = { _, _, _, _, _, _, _, _, _ ->
    error("native create must not run in lifecycle unit tests")
}

class NativePlayerControllerTeardownTest {
    @Test
    fun completesNavigationOnlyAfterNativeHandleIsDisposed() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val released = CountDownLatch(1)
        val host = NativePlayerHost()
        val controller = NativePlayerController(
            host = host,
            nativeCreate = failNativeCreate,
            nativeDispose = { handle -> events += "disposed-$handle" },
        )
        controller.setNativeHandleForTest(42L)

        controller.releaseBeforeNavigation {
            events += "released"
            released.countDown()
        }

        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("disposed-42", "released"), events.toList())
    }

    @Test
    fun releaseInvalidatesPendingAttachBeforeItsNativeCreateCanPublish() {
        val host = NativePlayerHost()
        val controller = NativePlayerController(host = host, nativeCreate = failNativeCreate, nativeDispose = {})

        controller.attach(
            sourceUrl = "https://example.invalid/video.mp4",
            sourceHeaders = emptyMap(),
            playWhenReady = true,
            initialPositionMs = 0L,
            decoderPriority = 0,
            nvidiaRtxSuperResolutionEnabled = false,
            onError = {},
        )

        assertNotNull(host.onPeerReady)
        assertNotNull(controller.pendingSourceForTest())

        controller.releaseBeforeNavigation {}

        assertNull(host.onPeerReady)
        assertNull(controller.pendingSourceForTest())
    }

    @Test
    fun sourceGapsDisposeSuccessfulSupersededCreatesBeforeReplacementPublication() {
        val sourceA = "https://example.invalid/a.mp4"
        val sourceB = "https://example.invalid/b.mp4"
        val createAStarted = CountDownLatch(1)
        val allowCreateA = CountDownLatch(1)
        val createBStarted = CountDownLatch(1)
        val allowCreateB = CountDownLatch(1)
        val disposedA = CountDownLatch(1)
        val disposedB = CountDownLatch(1)
        val createdSources = Collections.synchronizedList(mutableListOf<String>())
        val disposedHandles = Collections.synchronizedList(mutableListOf<Long>())
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = { _, source, _, _, _, _, _, _, _ ->
                createdSources += source
                when (source) {
                    sourceA -> {
                        createAStarted.countDown()
                        allowCreateA.await()
                        41L
                    }
                    sourceB -> {
                        createBStarted.countDown()
                        allowCreateB.await()
                        42L
                    }
                    else -> error("unexpected source: $source")
                }
            },
            nativeDispose = { handle ->
                disposedHandles += handle
                when (handle) {
                    41L -> disposedA.countDown()
                    42L -> disposedB.countDown()
                    else -> error("unexpected handle: $handle")
                }
            },
            isHostDisplayable = { true },
            resolveHostView = { 1L },
        )

        controller.attach(
            sourceUrl = sourceA,
            sourceHeaders = emptyMap(),
            playWhenReady = true,
            initialPositionMs = 0L,
            decoderPriority = 0,
            nvidiaRtxSuperResolutionEnabled = false,
            onError = {},
        )
        assertTrue(createAStarted.await(2, TimeUnit.SECONDS))

        controller.dispose()
        controller.attach(
            sourceUrl = sourceB,
            sourceHeaders = emptyMap(),
            playWhenReady = true,
            initialPositionMs = 0L,
            decoderPriority = 0,
            nvidiaRtxSuperResolutionEnabled = false,
            onError = {},
        )

        allowCreateA.countDown()
        assertTrue(disposedA.await(2, TimeUnit.SECONDS))
        assertTrue(createBStarted.await(2, TimeUnit.SECONDS))

        controller.dispose()
        allowCreateB.countDown()
        assertTrue(disposedB.await(2, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait {}

        assertEquals(listOf(sourceA, sourceB), createdSources.toList())
        assertEquals(listOf(41L, 42L), disposedHandles.toList())
        assertNull(controller.pendingSourceForTest())
    }

    @Test
    fun timedOutCreateWaitCannotReportAfterCapturedWorkerFinishesAndNewCreateStarts() {
        val oldCreateStarted = CountDownLatch(1)
        val allowOldCreate = CountDownLatch(1)
        val blockEdt = CountDownLatch(1)
        val allowEdt = CountDownLatch(1)
        val createWaitCompleted = CountDownLatch(1)
        val newCreateStarted = CountDownLatch(1)
        val allowNewCreate = CountDownLatch(1)
        val errors = AtomicInteger()
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = {},
            isHostDisplayable = { true },
            resolveHostView = { error("host resolution must remain deferred") },
            createWaitTimeoutMs = 50L,
            onCreateWaitCompleted = { createWaitCompleted.countDown() },
        )
        val oldCreate = thread(isDaemon = true, name = "test-old-native-create") {
            oldCreateStarted.countDown()
            allowOldCreate.await()
        }
        assertTrue(oldCreateStarted.await(2, TimeUnit.SECONDS))
        controller.addCreateInFlightForTest(oldCreate)
        controller.attach(
            sourceUrl = "https://example.invalid/replacement.m3u8",
            sourceHeaders = emptyMap(),
            playWhenReady = true,
            initialPositionMs = 0L,
            decoderPriority = 0,
            nvidiaRtxSuperResolutionEnabled = false,
            onError = { errors.incrementAndGet() },
        )
        SwingUtilities.invokeAndWait {}

        SwingUtilities.invokeLater {
            blockEdt.countDown()
            allowEdt.await()
        }
        assertTrue(blockEdt.await(2, TimeUnit.SECONDS))
        assertTrue(createWaitCompleted.await(2, TimeUnit.SECONDS))
        allowOldCreate.countDown()
        oldCreate.join(2_000L)
        assertFalse(oldCreate.isAlive)

        val newCreate = thread(isDaemon = true, name = "test-new-native-create") {
            newCreateStarted.countDown()
            allowNewCreate.await()
        }
        assertTrue(newCreateStarted.await(2, TimeUnit.SECONDS))
        controller.addCreateInFlightForTest(newCreate)

        allowEdt.countDown()
        SwingUtilities.invokeAndWait {}
        assertEquals(0, errors.get())

        allowNewCreate.countDown()
        controller.releaseBeforeNavigation({}, {})
    }

    @Test
    fun timedOutCreateWaitCannotReportErrorToReplacementSource() {
        val createStarted = CountDownLatch(1)
        val allowCreate = CountDownLatch(1)
        val blockEdt = CountDownLatch(1)
        val allowEdt = CountDownLatch(1)
        val createWaitCompleted = CountDownLatch(1)
        val replacementErrors = AtomicInteger()
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = {},
            isHostDisplayable = { true },
            resolveHostView = { error("host resolution must remain deferred") },
            createWaitTimeoutMs = 50L,
            onCreateWaitCompleted = { createWaitCompleted.countDown() },
        )
        val create = Thread {
            createStarted.countDown()
            allowCreate.await()
        }.apply { start() }
        assertTrue(createStarted.await(2, TimeUnit.SECONDS))
        controller.addCreateInFlightForTest(create)

        controller.attach(
            sourceUrl = "https://example.invalid/waiting.mp4",
            sourceHeaders = emptyMap(),
            playWhenReady = true,
            initialPositionMs = 0L,
            decoderPriority = 0,
            nvidiaRtxSuperResolutionEnabled = false,
            onError = {},
        )
        SwingUtilities.invokeAndWait {}
        SwingUtilities.invokeLater {
            blockEdt.countDown()
            allowEdt.await()
        }
        assertTrue(blockEdt.await(2, TimeUnit.SECONDS))
        assertTrue(createWaitCompleted.await(2, TimeUnit.SECONDS))

        controller.attach(
            sourceUrl = "https://example.invalid/replacement.mp4",
            sourceHeaders = emptyMap(),
            playWhenReady = true,
            initialPositionMs = 0L,
            decoderPriority = 0,
            nvidiaRtxSuperResolutionEnabled = false,
            onError = { replacementErrors.incrementAndGet() },
        )
        allowEdt.countDown()
        SwingUtilities.invokeAndWait {}

        assertEquals(0, replacementErrors.get())
        controller.releaseBeforeNavigation {}
        allowCreate.countDown()
        create.join(2_000)
        assertFalse(create.isAlive)
    }

    @Test
    fun replacementWaitsForSupersededNativeCreateBeforeResolvingHost() {
        val createStarted = CountDownLatch(1)
        val allowCreate = CountDownLatch(1)
        val resolvedHosts = AtomicInteger()
        val errors = AtomicInteger()
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = {},
            isHostDisplayable = { true },
            resolveHostView = {
                resolvedHosts.incrementAndGet()
                error("resolver should not run while prior create is blocked")
            },
        )
        val create = thread(isDaemon = true, name = "test-superseded-create") {
            createStarted.countDown()
            allowCreate.await()
        }
        assertTrue(createStarted.await(2, TimeUnit.SECONDS))
        controller.addCreateInFlightForTest(create)

        controller.attach(
            sourceUrl = "https://example.invalid/replacement.mp4",
            sourceHeaders = emptyMap(),
            playWhenReady = true,
            initialPositionMs = 0L,
            decoderPriority = 0,
            nvidiaRtxSuperResolutionEnabled = false,
            onError = { errors.incrementAndGet() },
        )
        SwingUtilities.invokeAndWait {}

        assertEquals(0, resolvedHosts.get())
        assertEquals(0, errors.get())
        val released = CountDownLatch(1)
        controller.releaseBeforeNavigation { released.countDown() }
        allowCreate.countDown()
        assertTrue(released.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun queuedAttachCannotResolveHostAfterReleaseStarts() {
        val edtBlocked = CountDownLatch(1)
        val allowEdt = CountDownLatch(1)
        val resolvedHosts = AtomicInteger()
        val errors = AtomicInteger()
        SwingUtilities.invokeLater {
            edtBlocked.countDown()
            allowEdt.await()
        }
        assertTrue(edtBlocked.await(2, TimeUnit.SECONDS))
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = {},
            isHostDisplayable = { true },
            resolveHostView = { resolvedHosts.incrementAndGet().toLong() },
        )
        controller.attach(
            sourceUrl = "https://example.invalid/queued.mp4",
            sourceHeaders = emptyMap(),
            playWhenReady = true,
            initialPositionMs = 0L,
            decoderPriority = 0,
            nvidiaRtxSuperResolutionEnabled = false,
            onError = { errors.incrementAndGet() },
        )

        controller.releaseBeforeNavigation {}
        allowEdt.countDown()
        SwingUtilities.invokeAndWait {}

        assertEquals(0, resolvedHosts.get())
        assertEquals(0, errors.get())
    }

    @Test
    fun attachIsRejectedAfterNavigationReleaseStarts() {
        val host = NativePlayerHost()
        val controller = NativePlayerController(host = host, nativeCreate = failNativeCreate, nativeDispose = {})
        val releaseCompleted = CountDownLatch(1)

        controller.releaseBeforeNavigation { releaseCompleted.countDown() }
        assertTrue(releaseCompleted.await(2, TimeUnit.SECONDS))
        controller.attach(
            sourceUrl = "https://example.invalid/late.mp4",
            sourceHeaders = emptyMap(),
            playWhenReady = true,
            initialPositionMs = 0L,
            decoderPriority = 0,
            nvidiaRtxSuperResolutionEnabled = false,
            onError = {},
        )

        assertNull(host.onPeerReady)
        assertNull(controller.pendingSourceForTest())
    }

    @Test
    fun sourceReplacementDisposePreservesControlCallbacks() {
        val host = NativePlayerHost()
        val controller = NativePlayerController(host = host, nativeCreate = failNativeCreate, nativeDispose = {})
        controller.setControlCallbacks(
            onAction = { true },
            onEvent = { _, _ -> true },
            onScrubChange = { true },
            onScrubFinished = { true },
        )
        assertNotNull(host.onCursorActivity)

        controller.dispose()

        assertNotNull(host.onCursorActivity)
    }

    @Test
    fun releaseWaitsForTrackedRejectedHandleDisposal() {
        val disposeStarted = CountDownLatch(1)
        val allowDispose = CountDownLatch(1)
        val released = CountDownLatch(1)
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = {
                disposeStarted.countDown()
                allowDispose.await()
            },
        )

        controller.startTrackedDisposeForTest(42L)
        assertTrue(disposeStarted.await(2, TimeUnit.SECONDS))
        controller.releaseBeforeNavigation { released.countDown() }

        assertFalse(released.await(100, TimeUnit.MILLISECONDS))
        allowDispose.countDown()
        assertTrue(released.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun trackedDisposalFailureRejectsRetryBeforeHostResolution() {
        val displayable = AtomicBoolean(false)
        val hostResolutions = AtomicInteger()
        val retryError = CountDownLatch(1)
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = { error("dispose failed") },
            isHostDisplayable = { displayable.get() },
            resolveHostView = {
                hostResolutions.incrementAndGet()
                error("host resolution must be rejected")
            },
        )
        controller.attach(
            sourceUrl = "https://example.invalid/pending.m3u8",
            sourceHeaders = emptyMap(),
            playWhenReady = true,
            initialPositionMs = 0L,
            decoderPriority = 0,
            nvidiaRtxSuperResolutionEnabled = false,
            onError = { retryError.countDown() },
        )
        controller.startTrackedDisposeForTest(42L)
        controller.disposeInFlightForTest()?.join(2_000L)
        displayable.set(true)

        controller.retry()
        SwingUtilities.invokeAndWait {}

        assertTrue(retryError.await(2, TimeUnit.SECONDS))
        assertEquals(0, hostResolutions.get())
    }

    @Test
    fun failedOrdinaryDisposeRejectsReplacementBeforeHostResolution() {
        val disposeStarted = CountDownLatch(1)
        val allowDisposeFailure = CountDownLatch(1)
        val hostResolutions = AtomicInteger()
        val replacementError = CountDownLatch(1)
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = {
                disposeStarted.countDown()
                allowDisposeFailure.await()
                error("dispose failed")
            },
            isHostDisplayable = { true },
            resolveHostView = {
                hostResolutions.incrementAndGet()
                error("host resolution must be rejected")
            },
        )
        controller.setNativeHandleForTest(42L)

        controller.dispose()
        assertTrue(disposeStarted.await(2, TimeUnit.SECONDS))
        allowDisposeFailure.countDown()
        controller.disposeInFlightForTest()?.join(2_000L)
        controller.attach(
            sourceUrl = "https://example.invalid/replacement.m3u8",
            sourceHeaders = emptyMap(),
            playWhenReady = true,
            initialPositionMs = 0L,
            decoderPriority = 0,
            nvidiaRtxSuperResolutionEnabled = false,
            onError = { replacementError.countDown() },
        )
        SwingUtilities.invokeAndWait {}

        assertTrue(replacementError.await(2, TimeUnit.SECONDS))
        assertEquals(0, hostResolutions.get())
    }

    @Test
    fun failedOrdinaryDisposeBlocksTerminalNavigation() {
        val disposeAttempted = CountDownLatch(1)
        val released = AtomicInteger()
        val failed = CountDownLatch(1)
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = {
                disposeAttempted.countDown()
                error("dispose failed")
            },
        )
        controller.setNativeHandleForTest(42L)

        controller.dispose()
        assertTrue(disposeAttempted.await(2, TimeUnit.SECONDS))
        controller.releaseBeforeNavigation(
            onReleased = { released.incrementAndGet() },
            onReleaseFailed = { failed.countDown() },
        )

        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertEquals(0, released.get())
    }

    @Test
    fun nativeDisposeFailureCannotBecomeSuccessfulOnRetry() {
        val failures = AtomicInteger()
        val completions = AtomicInteger()
        val firstFailure = CountDownLatch(1)
        val retryFailure = CountDownLatch(1)
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = { error("dispose failed") },
        )
        controller.setNativeHandleForTest(42L)

        controller.releaseBeforeNavigation(
            onReleased = { completions.incrementAndGet() },
            onReleaseFailed = {
                if (failures.incrementAndGet() == 1) firstFailure.countDown()
            },
        )
        assertTrue(firstFailure.await(2, TimeUnit.SECONDS))
        controller.releaseBeforeNavigation(
            onReleased = { completions.incrementAndGet() },
            onReleaseFailed = {
                failures.incrementAndGet()
                retryFailure.countDown()
            },
        )

        assertTrue(retryFailure.await(2, TimeUnit.SECONDS))
        assertEquals(0, completions.get())
        assertEquals(2, failures.get())
    }

    @Test
    fun desktopReleaseWaitsForNativeDispose() {
        val disposeStarted = CountDownLatch(1)
        val allowDispose = CountDownLatch(1)
        val released = CountDownLatch(1)
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = {
                disposeStarted.countDown()
                allowDispose.await()
            },
        )
        controller.setNativeHandleForTest(42L)

        controller.releaseBeforeNavigation { released.countDown() }

        assertTrue(disposeStarted.await(2, TimeUnit.SECONDS))
        assertFalse(released.await(100, TimeUnit.MILLISECONDS))
        allowDispose.countDown()
        assertTrue(released.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun blockedNativeDisposeReportsFailureWithoutCompletingNavigation() {
        val disposeStarted = CountDownLatch(1)
        val allowDispose = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val disposeFinished = CountDownLatch(1)
        val completed = AtomicInteger()
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = {
                disposeStarted.countDown()
                allowDispose.await()
                disposeFinished.countDown()
            },
            releaseTimeoutMs = 50L,
        )
        controller.setNativeHandleForTest(42L)

        controller.releaseBeforeNavigation(
            onReleased = { completed.incrementAndGet() },
            onReleaseFailed = {
                assertTrue(SwingUtilities.isEventDispatchThread())
                failed.countDown()
            },
        )

        assertTrue(disposeStarted.await(2, TimeUnit.SECONDS))
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertEquals(0, completed.get())
        allowDispose.countDown()
        assertTrue(disposeFinished.await(2, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait {}
        assertEquals(0, completed.get())
    }

    @Test
    fun blockedNativeCreateReportsFailureWithoutCompletingNavigation() {
        val createStarted = CountDownLatch(1)
        val allowCreate = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val completed = AtomicInteger()
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = {},
            releaseTimeoutMs = 50L,
        )
        val create = Thread {
            createStarted.countDown()
            allowCreate.await()
        }.apply { start() }
        assertTrue(createStarted.await(2, TimeUnit.SECONDS))
        controller.addCreateInFlightForTest(create)

        controller.releaseBeforeNavigation(
            onReleased = { completed.incrementAndGet() },
            onReleaseFailed = {
                assertTrue(SwingUtilities.isEventDispatchThread())
                failed.countDown()
            },
        )

        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertEquals(0, completed.get())
        allowCreate.countDown()
        create.join(2_000)
        assertFalse(create.isAlive)
        SwingUtilities.invokeAndWait {}
        assertEquals(0, completed.get())
    }

    @Test
    fun repeatedReleaseCallbacksCompleteOnceOnSwingEdt() {
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val done = CountDownLatch(2)
        val calls = AtomicInteger()
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = {
                started.countDown()
                unblock.await()
            },
        )
        controller.setNativeHandleForTest(42L)
        repeat(2) {
            controller.releaseBeforeNavigation {
                assertTrue(SwingUtilities.isEventDispatchThread())
                calls.incrementAndGet()
                done.countDown()
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertFalse(done.await(100, TimeUnit.MILLISECONDS))
        unblock.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(2, calls.get())
    }

    @Test
    fun releaseCompletionWaitsForEarlierNativeTeardown() {
        val teardownStarted = CountDownLatch(1)
        val allowTeardown = CountDownLatch(1)
        val releaseCompleted = CountDownLatch(1)
        val controller = NativePlayerController(host = NativePlayerHost(), nativeCreate = failNativeCreate, nativeDispose = {})
        val teardown = thread(isDaemon = true, name = "test-native-teardown") {
            teardownStarted.countDown()
            allowTeardown.await()
        }
        assertTrue(teardownStarted.await(2, TimeUnit.SECONDS))
        controller.setDisposeInFlightForTest(teardown)
        controller.releaseBeforeNavigation { releaseCompleted.countDown() }
        assertFalse(releaseCompleted.await(100, TimeUnit.MILLISECONDS))
        allowTeardown.countDown()
        assertTrue(releaseCompleted.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun releaseCompletionWaitsForDisposeStartedWhileCreateIsFinishing() {
        val createStarted = CountDownLatch(1)
        val allowCreate = CountDownLatch(1)
        val disposeStarted = CountDownLatch(1)
        val allowDispose = CountDownLatch(1)
        val releaseCompleted = CountDownLatch(1)
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = {
                disposeStarted.countDown()
                allowDispose.await()
            },
        )
        controller.setNativeHandleForTest(42L)
        val create = thread(isDaemon = true, name = "test-native-create") {
            createStarted.countDown()
            allowCreate.await()
        }
        assertTrue(createStarted.await(2, TimeUnit.SECONDS))
        controller.addCreateInFlightForTest(create)

        controller.releaseBeforeNavigation { releaseCompleted.countDown() }
        controller.dispose()
        allowCreate.countDown()
        assertTrue(disposeStarted.await(2, TimeUnit.SECONDS))

        assertFalse(releaseCompleted.await(100, TimeUnit.MILLISECONDS))
        allowDispose.countDown()
        assertTrue(releaseCompleted.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun releaseCompletionWaitsForInFlightNativeCreate() {
        val host = NativePlayerHost()
        val controller = NativePlayerController(host = host, nativeCreate = failNativeCreate, nativeDispose = {})
        val createStarted = CountDownLatch(1)
        val allowCreate = CountDownLatch(1)
        val releaseCompleted = CountDownLatch(1)
        val create = thread(isDaemon = true, name = "test-native-create") {
            createStarted.countDown()
            allowCreate.await()
        }
        assertTrue(createStarted.await(2, TimeUnit.SECONDS))
        controller.addCreateInFlightForTest(create)

        controller.releaseBeforeNavigation { releaseCompleted.countDown() }

        assertFalse(releaseCompleted.await(100, TimeUnit.MILLISECONDS))
        allowCreate.countDown()
        assertTrue(releaseCompleted.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun releaseCompletionWaitsForDisposalQueuedByFinishingCreate() {
        val edtBlocked = CountDownLatch(1)
        val allowEdt = CountDownLatch(1)
        val createStarted = CountDownLatch(1)
        val allowCreate = CountDownLatch(1)
        val disposeStarted = CountDownLatch(1)
        val allowDispose = CountDownLatch(1)
        val releaseCompleted = CountDownLatch(1)
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = {
                disposeStarted.countDown()
                allowDispose.await()
            },
        )
        SwingUtilities.invokeLater {
            edtBlocked.countDown()
            allowEdt.await()
        }
        assertTrue(edtBlocked.await(2, TimeUnit.SECONDS))
        val create = thread(isDaemon = true, name = "test-native-create") {
            createStarted.countDown()
            allowCreate.await()
            SwingUtilities.invokeLater {
                controller.startTrackedDisposeForTest(42L)
            }
        }
        assertTrue(createStarted.await(2, TimeUnit.SECONDS))
        controller.addCreateInFlightForTest(create)

        controller.releaseBeforeNavigation { releaseCompleted.countDown() }
        allowCreate.countDown()
        create.join(2_000L)
        assertFalse(create.isAlive)
        allowEdt.countDown()
        assertTrue(disposeStarted.await(2, TimeUnit.SECONDS))

        assertFalse(releaseCompleted.await(100, TimeUnit.MILLISECONDS))
        allowDispose.countDown()
        assertTrue(releaseCompleted.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun timedOutQueuedDisposalNeverCompletesNavigationLater() {
        val edtBlocked = CountDownLatch(1)
        val allowEdt = CountDownLatch(1)
        val createStarted = CountDownLatch(1)
        val allowCreate = CountDownLatch(1)
        val disposeStarted = CountDownLatch(1)
        val allowDispose = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val failures = AtomicInteger()
        val completions = AtomicInteger()
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = failNativeCreate,
            nativeDispose = {
                disposeStarted.countDown()
                allowDispose.await()
            },
            releaseTimeoutMs = 50L,
        )
        SwingUtilities.invokeLater {
            edtBlocked.countDown()
            allowEdt.await()
        }
        assertTrue(edtBlocked.await(2, TimeUnit.SECONDS))
        val create = thread(isDaemon = true, name = "test-native-create") {
            createStarted.countDown()
            allowCreate.await()
            SwingUtilities.invokeLater {
                controller.startTrackedDisposeForTest(42L)
            }
        }
        assertTrue(createStarted.await(2, TimeUnit.SECONDS))
        controller.addCreateInFlightForTest(create)

        controller.releaseBeforeNavigation(
            onReleased = { completions.incrementAndGet() },
            onReleaseFailed = {
                assertTrue(SwingUtilities.isEventDispatchThread())
                failures.incrementAndGet()
                failed.countDown()
            },
        )
        allowCreate.countDown()
        create.join(2_000L)
        assertFalse(create.isAlive)
        allowEdt.countDown()
        assertTrue(disposeStarted.await(2, TimeUnit.SECONDS))
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertEquals(1, failures.get())
        assertEquals(0, completions.get())

        allowDispose.countDown()
        controller.disposeInFlightForTest()?.join(2_000L)
        SwingUtilities.invokeAndWait {}
        assertEquals(1, failures.get())
        assertEquals(0, completions.get())
    }
}

private fun NativePlayerController.pendingSourceForTest(): Any? =
    javaClass.getDeclaredField("pendingSource").let { field ->
        field.isAccessible = true
        field.get(this)
    }

private fun NativePlayerController.startTrackedDisposeForTest(value: Long) {
    javaClass.getDeclaredMethod("startTrackedDisposeLocked", Long::class.javaPrimitiveType).also { method ->
        method.isAccessible = true
        method.invoke(this, value)
    }
}

private fun NativePlayerController.setNativeHandleForTest(value: Long) {
    javaClass.getDeclaredField("handle").also { field ->
        field.isAccessible = true
        field.setLong(this, value)
    }
}

private fun NativePlayerController.disposeInFlightForTest(): Thread? =
    javaClass.getDeclaredField("disposeInFlight").let { field ->
        field.isAccessible = true
        field.get(this) as? Thread
    }

private fun NativePlayerController.setDisposeInFlightForTest(value: Thread) {
    javaClass.getDeclaredField("disposeInFlight").also { field ->
        field.isAccessible = true
        field.set(this, value)
    }
}

@Suppress("UNCHECKED_CAST")
private fun NativePlayerController.addCreateInFlightForTest(value: Thread) {
    javaClass.getDeclaredField("createsInFlight").also { field ->
        field.isAccessible = true
        (field.get(this) as MutableSet<Thread>) += value
    }
}
