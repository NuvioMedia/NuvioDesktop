package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDownloadResumeTest {
    @Test
    fun dropped_connections_resume_from_the_partial_file_instead_of_failing() {
        val payload = Random(42).nextBytes(3 * 1024 * 1024)
        val dropAfterBytes = 512 * 1024
        val droppedResponses = 2
        val requestCount = AtomicInteger(0)
        val rangeHeaders = mutableListOf<String?>()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mkv") { exchange ->
            val requestIndex = requestCount.getAndIncrement()
            val range = exchange.requestHeaders.getFirst("Range")
            synchronized(rangeHeaders) { rangeHeaders += range }
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val body = payload.copyOfRange(start, payload.size)
            if (start > 0) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
            }
            exchange.sendResponseHeaders(if (start > 0) 206 else 200, body.size.toLong())
            exchange.responseBody.use { output ->
                if (requestIndex < droppedResponses) {
                    output.write(body, 0, dropAfterBytes)
                    output.flush()
                    // Close the socket mid-body, like a CDN or flaky network dropping the connection.
                    exchange.close()
                } else {
                    output.write(body)
                }
            }
        }
        server.start()

        val fileName = "nuvio-resume-test-${System.nanoTime()}.mkv"
        val downloadsDir = DesktopStorage.rootDir.resolve("downloads").toFile()
        val completed = CountDownLatch(1)
        var successUri: String? = null
        var failureMessage: String? = null

        try {
            DownloadsPlatformDownloader.start(
                request = DownloadPlatformRequest(
                    testItem(
                        sourceUrl = "http://127.0.0.1:${server.address.port}/video.mkv",
                        fileName = fileName,
                    ),
                ),
                onProgress = { _, _ -> },
                onSuccess = { uri, _ ->
                    successUri = uri
                    completed.countDown()
                },
                onFailure = { message ->
                    failureMessage = message
                    completed.countDown()
                },
                onPaused = { completed.countDown() },
            )

            assertTrue(completed.await(60, TimeUnit.SECONDS), "download did not finish")
            assertNull(failureMessage, "download failed instead of resuming")
            val downloaded = File(URI(successUri!!))
            assertContentEquals(payload, downloaded.readBytes())
            assertEquals(droppedResponses + 1, requestCount.get())
            synchronized(rangeHeaders) {
                assertNull(rangeHeaders.first())
                assertTrue(rangeHeaders.drop(1).all { it != null && it.startsWith("bytes=") && it != "bytes=0-" })
            }
        } finally {
            server.stop(0)
            File(downloadsDir, fileName).delete()
            File(downloadsDir, "$fileName.part").delete()
        }
    }

    private fun testItem(sourceUrl: String, fileName: String) = DownloadItem(
        id = "resume-test",
        contentType = "movie",
        parentMetaId = "tt0000000",
        parentMetaType = "movie",
        videoId = "tt0000000",
        title = "Resume test",
        streamTitle = "Resume test",
        providerName = "test",
        sourceUrl = sourceUrl,
        fileName = fileName,
        status = DownloadStatus.Downloading,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )
}
