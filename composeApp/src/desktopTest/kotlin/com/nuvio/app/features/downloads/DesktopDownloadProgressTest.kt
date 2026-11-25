package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDownloadProgressTest {
    @Test
    fun slow_download_reports_progress_about_once_per_second_not_per_chunk() {
        val chunk = 64 * 1024
        val chunks = 10
        val payload = Random(7).nextBytes(chunk * chunks)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mkv") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { output ->
                repeat(chunks) { index ->
                    output.write(payload, index * chunk, chunk)
                    output.flush()
                    Thread.sleep(250)
                }
            }
        }
        server.start()

        val fileName = "nuvio-progress-test-${System.nanoTime()}.mkv"
        val downloadsDir = DesktopStorage.rootDir.resolve("downloads").toFile()
        val completed = CountDownLatch(1)
        val reported = mutableListOf<Long>()
        var failureMessage: String? = null

        try {
            DownloadsPlatformDownloader.start(
                request = DownloadPlatformRequest(
                    DownloadItem(
                        id = "progress-test",
                        contentType = "movie",
                        parentMetaId = "tt0000000",
                        parentMetaType = "movie",
                        videoId = "tt0000000",
                        title = "Progress test",
                        streamTitle = "Progress test",
                        providerName = "test",
                        sourceUrl = "http://127.0.0.1:${server.address.port}/video.mkv",
                        fileName = fileName,
                        status = DownloadStatus.Downloading,
                        createdAtEpochMs = 0L,
                        updatedAtEpochMs = 0L,
                    ),
                ),
                onProgress = { bytes, _ -> synchronized(reported) { reported += bytes } },
                onSuccess = { _, _ -> completed.countDown() },
                onFailure = { message ->
                    failureMessage = message
                    completed.countDown()
                },
                onPaused = { completed.countDown() },
            )

            assertTrue(completed.await(30, TimeUnit.SECONDS), "download did not finish")
            assertNull(failureMessage)
            synchronized(reported) {
                assertTrue(reported.size in 3..5, "expected a handful of progress reports, got ${reported.size}")
                assertEquals(reported.sorted(), reported)
                assertEquals(payload.size.toLong(), reported.last())
            }
        } finally {
            server.stop(0)
            File(downloadsDir, fileName).delete()
            File(downloadsDir, "$fileName.part").delete()
        }
    }
}
