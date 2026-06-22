package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.URI

internal data class DesktopAddonHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val followRedirects: Boolean,
)

internal class DesktopAddonHttpTransport(
    maxConcurrentRequests: Int = DEFAULT_MAX_CONCURRENT_REQUESTS,
    private val retryDelaysMillis: List<Long> = DEFAULT_RETRY_DELAYS_MILLIS,
    private val delayBeforeRetry: suspend (Long) -> Unit = { delay(it) },
    private val send: suspend (DesktopAddonHttpRequest) -> RawHttpResponse,
) {
    private val semaphore = Semaphore(maxConcurrentRequests.coerceAtLeast(1))

    suspend fun execute(request: DesktopAddonHttpRequest): RawHttpResponse =
        semaphore.withPermit {
            var retryIndex = 0
            while (true) {
                try {
                    return@withPermit send(request)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IOException) {
                    val delayMillis = retryDelaysMillis.getOrNull(retryIndex)
                    if (delayMillis == null || !isTransientDesktopAddonHttpFailure(error)) {
                        throw error
                    }
                    log.w(error) {
                        "Retrying desktop addon HTTP ${request.method} request to ${request.sanitizedHost()} " +
                            "after transient failure (${retryIndex + 1}/${retryDelaysMillis.size}): ${error.message}"
                    }
                    retryIndex += 1
                    delayBeforeRetry(delayMillis)
                }
            }
            error("unreachable")
        }

    private fun DesktopAddonHttpRequest.sanitizedHost(): String =
        runCatching { URI(url).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "unknown-host"

    companion object {
        private val log = Logger.withTag("DesktopAddonHttp")
        private const val DEFAULT_MAX_CONCURRENT_REQUESTS = 8
        private val DEFAULT_RETRY_DELAYS_MILLIS = listOf(150L, 400L)
    }
}

internal fun isTransientDesktopAddonHttpFailure(error: Throwable): Boolean {
    if (error !is IOException) return false

    val message = generateSequence(error as Throwable?) { it.cause }
        .mapNotNull { it.message }
        .joinToString(separator = " ")
        .lowercase()

    return transientSocketMessages.any { it in message }
}

private val transientSocketMessages = listOf(
    "can't assign requested address",
    "cannot assign requested address",
    "address not available",
    "connection reset",
    "broken pipe",
    "connection timed out",
    "read timed out",
)
