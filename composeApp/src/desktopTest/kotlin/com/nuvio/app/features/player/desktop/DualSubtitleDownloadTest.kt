package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.autosync.AutoSyncSubtitleHttp
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DualSubtitleDownloadTest {
    @Test
    fun downloadPreservesOriginalBytesIncludingBitmapPayloads() = runTest {
        val bytes = byteArrayOf(0, 1, 2, -1, -2, 0, 13, 10)
        withSubtitleServer(bytes) { url ->
            val response = AutoSyncSubtitleHttp.get(url, emptyMap(), 1024, requireComplete = true)
            assertContentEquals(bytes, response.bodyBytes)
        }
    }

    @Test
    fun oversizedDualSubtitleIsRejectedInsteadOfDisplayingTruncatedContent() = runTest {
        withSubtitleServer(ByteArray(64) { 'a'.code.toByte() }) { url ->
            assertFailsWith<IOException> {
                AutoSyncSubtitleHttp.get(url, emptyMap(), 16, requireComplete = true)
            }
        }
    }

    @Test
    fun existingAutoSyncTruncationBehaviorIsPreserved() = runTest {
        withSubtitleServer(ByteArray(64) { 'a'.code.toByte() }) { url ->
            val response = AutoSyncSubtitleHttp.get(url, emptyMap(), 16)
            assertEquals(16, response.bodyBytes.size)
            assertTrue(response.body.endsWith("...[truncated]"))
        }
    }

    @Test
    fun cancellingASelectionCancelsItsPendingHttpDownload() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/subtitle") { exchange ->
                started.countDown()
                try { release.await() } finally { exchange.close() }
            }
            start()
        }
        try {
            val request = async(Dispatchers.Default) {
                AutoSyncSubtitleHttp.get("http://127.0.0.1:${server.address.port}/subtitle", emptyMap(), 1024)
            }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
        } finally {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private suspend fun withSubtitleServer(bytes: ByteArray, test: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/subtitle") { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            test("http://127.0.0.1:${server.address.port}/subtitle")
        } finally {
            server.stop(0)
        }
    }
}
