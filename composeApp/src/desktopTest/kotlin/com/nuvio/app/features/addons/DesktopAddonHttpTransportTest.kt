package com.nuvio.app.features.addons

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopAddonHttpTransportTest {
    @Test
    fun `retries transient address assignment failures`() = runBlocking {
        var attempts = 0
        val transport = DesktopAddonHttpTransport(
            send = {
                attempts += 1
                if (attempts < 3) {
                    throw IOException("Can't assign requested address")
                }
                rawResponse()
            },
            retryDelaysMillis = listOf(1L, 1L),
            delayBeforeRetry = {},
        )

        val response = transport.execute(request())

        assertEquals(3, attempts)
        assertEquals("ok", response.body)
    }

    @Test
    fun `does not retry non transient failures`() = runBlocking {
        var attempts = 0
        val transport = DesktopAddonHttpTransport(
            send = {
                attempts += 1
                throw IOException("Malformed response")
            },
            retryDelaysMillis = listOf(1L, 1L),
            delayBeforeRetry = {},
        )

        assertFailsWith<IOException> {
            transport.execute(request())
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `does not retry cancellation`() = runBlocking {
        var attempts = 0
        val transport = DesktopAddonHttpTransport(
            send = {
                attempts += 1
                throw CancellationException("cancelled")
            },
            retryDelaysMillis = listOf(1L, 1L),
            delayBeforeRetry = {},
        )

        assertFailsWith<CancellationException> {
            transport.execute(request())
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `limits concurrent requests`() = runBlocking {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val twoRequestsActive = CompletableDeferred<Unit>()
        val releaseRequests = CompletableDeferred<Unit>()
        val transport = DesktopAddonHttpTransport(
            maxConcurrentRequests = 2,
            send = {
                val current = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, current) }
                if (current == 2) {
                    twoRequestsActive.complete(Unit)
                }
                releaseRequests.await()
                active.decrementAndGet()
                rawResponse()
            },
            retryDelaysMillis = emptyList(),
            delayBeforeRetry = {},
        )

        coroutineScope {
            val jobs = (1..5).map {
                async(Dispatchers.Default) {
                    transport.execute(request())
                }
            }
            twoRequestsActive.await()
            delay(50L)
            assertEquals(2, maxActive.get())
            releaseRequests.complete(Unit)
            jobs.awaitAll()
        }
        Unit
    }
}

private fun request() = DesktopAddonHttpRequest(
    method = "GET",
    url = "https://example.test/manifest.json",
    headers = emptyMap(),
    body = "",
    followRedirects = true,
)

private fun rawResponse() = RawHttpResponse(
    status = 200,
    statusText = "HTTP 200",
    url = "https://example.test/manifest.json",
    body = "ok",
    headers = emptyMap(),
)
