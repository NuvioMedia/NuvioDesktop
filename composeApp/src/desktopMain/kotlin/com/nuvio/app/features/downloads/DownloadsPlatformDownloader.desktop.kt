package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.io.path.createDirectories

private val desktopDownloadHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

internal actual object DownloadsPlatformDownloader {
    private val downloadsDir: File
        get() = File(DesktopStorage.rootDir.resolve("downloads").also { it.createDirectories() }.toUri())

    actual fun restoreItem(item: DownloadItem): DownloadItem =
        if (item.status == DownloadStatus.Downloading) {
            item.copy(status = DownloadStatus.Paused, errorMessage = null)
        } else {
            item
        }

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)

        scope.launch {
            val destination = File(downloadsDir, request.destinationFileName)
            val tempFile = File(downloadsDir, "${request.destinationFileName}.part")

            // Dropped connections are retried in place (resuming from the .part file) with backoff.
            // The retry budget resets whenever an attempt makes progress, so long downloads survive
            // periodic disconnects and only fail after repeated attempts that receive nothing.
            var failedAttemptsWithoutProgress = 0
            while (true) {
                val bytesBeforeAttempt = tempFile.partialLength()
                try {
                    val totalBytes = transferToTempFile(request, tempFile, onProgress)

                    if (destination.exists()) {
                        destination.delete()
                    }
                    if (!tempFile.renameTo(destination)) {
                        tempFile.copyTo(destination, overwrite = true)
                        tempFile.delete()
                    }

                    val finalSize = destination.length()
                    onSuccess(destination.toURI().toString(), totalBytes ?: finalSize)
                    return@launch
                } catch (error: CancellationException) {
                    onPaused()
                    throw error
                } catch (error: IOException) {
                    if (tempFile.partialLength() > bytesBeforeAttempt) {
                        failedAttemptsWithoutProgress = 0
                    }
                    failedAttemptsWithoutProgress++
                    if (failedAttemptsWithoutProgress > MaxTransientRetries) {
                        onFailure(error.message?.takeIf { it.isNotBlank() && it != "closed" } ?: "Connection lost")
                        return@launch
                    }
                    delay(retryDelayMs(failedAttemptsWithoutProgress))
                } catch (error: Throwable) {
                    onFailure(error.message ?: "Download failed")
                    return@launch
                }
            }
        }

        return DesktopDownloadsTaskHandle(job)
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val tempFile = File(downloadsDir, "$destinationFileName.part")
        if (!tempFile.exists()) return true
        return runCatching { tempFile.delete() }.getOrDefault(false)
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalFileOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: return null
        return File(downloadsDir, fileName).takeIf { it.exists() }?.toURI()?.toString()
    }

    actual fun openDownloadsDirectory(): Boolean {
        val directory = downloadsDir
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()

        if (desktop != null && Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.OPEN)) {
            val opened = runCatching { desktop.open(directory) }.isSuccess
            if (opened) return true
        }

        return openDirectoryWithPlatformCommand(directory)
    }

    /** Downloads into [tempFile], resuming from its current length when possible. Returns the total size if known. */
    private suspend fun transferToTempFile(
        request: DownloadPlatformRequest,
        tempFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Long? {
        var resumeFromBytes = tempFile.partialLength()
        var attemptedRangeRequest = resumeFromBytes > 0L
        var response = sendDownloadRequest(request, if (attemptedRangeRequest) resumeFromBytes else null)

        if (attemptedRangeRequest && response.statusCode() == 416) {
            response.body().close()
            tempFile.delete()
            resumeFromBytes = 0L
            attemptedRangeRequest = false
            response = sendDownloadRequest(request, null)
        }

        val statusCode = response.statusCode()
        if (statusCode !in 200..299) {
            response.body().close()
            if (statusCode == 408 || statusCode == 429 || statusCode in 500..599) {
                throw IOException("Download failed with HTTP $statusCode")
            }
            error("Download failed with HTTP $statusCode")
        }

        val isPartialResume = attemptedRangeRequest && statusCode == 206 && resumeFromBytes > 0L
        val appendToTemp = isPartialResume
        val startingBytes = if (appendToTemp) resumeFromBytes else 0L
        if (!appendToTemp && tempFile.exists()) {
            tempFile.delete()
        }

        val totalBytes = resolveTotalBytes(
            startingBytes = startingBytes,
            isPartialResume = isPartialResume,
            contentRangeHeader = response.headers().firstValue("Content-Range").orElse(null),
            contentLength = response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull(),
        )
        var downloadedBytes = startingBytes
        onProgress(downloadedBytes, totalBytes)

        response.body().use { input ->
            FileOutputStream(tempFile, appendToTemp).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read.toLong()
                    onProgress(downloadedBytes, totalBytes)
                }
                output.flush()
            }
        }

        // A server that ends the stream early would otherwise leave a truncated file marked as completed.
        if (totalBytes != null && downloadedBytes < totalBytes) {
            throw IOException("Connection closed after $downloadedBytes of $totalBytes bytes")
        }
        return totalBytes
    }

    private fun sendDownloadRequest(
        request: DownloadPlatformRequest,
        rangeStart: Long?,
    ): HttpResponse<java.io.InputStream> {
        val builder = HttpRequest.newBuilder()
            .uri(URI(request.sourceUrl))
            .timeout(Duration.ofSeconds(60))
            .GET()
        request.sourceHeaders.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                builder.header(key, value)
            }
        }
        if (rangeStart != null && rangeStart > 0L) {
            builder.header("Range", "bytes=$rangeStart-")
        }
        return desktopDownloadHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
    }
}

private fun openDirectoryWithPlatformCommand(directory: File): Boolean {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val command = when {
        osName.contains("mac") -> listOf("open", directory.absolutePath)
        osName.contains("win") -> listOf("explorer", directory.absolutePath)
        else -> listOf("xdg-open", directory.absolutePath)
    }
    return runCatching { ProcessBuilder(command).start() }.isSuccess
}

private class DesktopDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    override fun cancel() {
        job.cancel()
    }
}

private const val MaxTransientRetries = 10
private const val MaxRetryDelayMs = 30_000L

/** 2s, 4s, 8s, 16s, then capped at 30s. */
private fun retryDelayMs(failedAttempts: Int): Long =
    (1_000L shl failedAttempts.coerceIn(1, 5)).coerceAtMost(MaxRetryDelayMs)

private fun File.partialLength(): Long =
    takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L

private fun String.toLocalFileOrNull(): File? =
    runCatching {
        if (startsWith("file:")) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()

private fun resolveTotalBytes(
    startingBytes: Long,
    isPartialResume: Boolean,
    contentRangeHeader: String?,
    contentLength: Long?,
): Long? {
    parseContentRangeTotal(contentRangeHeader)?.let { return it }
    val normalizedLength = contentLength?.takeIf { it > 0L } ?: return null
    return if (isPartialResume && startingBytes > 0L) {
        startingBytes + normalizedLength
    } else {
        normalizedLength
    }
}

private fun parseContentRangeTotal(headerValue: String?): Long? {
    val value = headerValue?.trim().orEmpty()
    if (value.isBlank()) return null
    val slashIndex = value.lastIndexOf('/')
    if (slashIndex == -1 || slashIndex == value.lastIndex) return null
    val totalPart = value.substring(slashIndex + 1).trim()
    if (totalPart == "*") return null
    return totalPart.toLongOrNull()?.takeIf { it > 0L }
}
