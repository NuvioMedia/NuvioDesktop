package com.nuvio.app.features.player

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real libdbus against a socket that stalls before a ScreenSaver call can be sent. */
class LinuxScreenSaverConnectionTest {
    @Before
    fun requireLinux() = assumeTrue(System.getProperty("os.name").contains("linux", ignoreCase = true))

    @Test
    fun stalledAuthenticationTimesOutAndAllowsFallbackAndStop() {
        exerciseStalledConnection(authenticate = false, disposeDuringAcquire = false)
    }

    @Test
    fun disposeDuringStalledAuthenticationDrainsTheWorkerAndClosesTheSocket() {
        exerciseStalledConnection(authenticate = false, disposeDuringAcquire = true)
    }

    @Test
    fun stalledHelloTimesOutAndAllowsFallbackAndStop() {
        exerciseStalledConnection(authenticate = true, disposeDuringAcquire = false)
    }

    private fun exerciseStalledConnection(authenticate: Boolean, disposeDuringAcquire: Boolean) {
        val acquireWorker = CompletableFuture<Thread>()
        val fallbackStarted = CountDownLatch(1)
        lateinit var child: Process
        StalledBus(authenticate).use { bus ->
            val controller = LinuxKeepAwakeController(
                acquireScreenSaver = {
                    acquireWorker.complete(Thread.currentThread())
                    LinuxScreenSaverInhibitor.acquire(bus.address)
                },
                startSystemdInhibit = {
                    ProcessBuilder("cat").redirectOutput(ProcessBuilder.Redirect.DISCARD).start().also {
                        child = it
                        fallbackStarted.countDown()
                    }
                },
            )
            try {
                controller.setEnabled(true)
                bus.awaitStall()
                if (disposeDuringAcquire) controller.close()
                assertTrue(fallbackStarted.await(5, TimeUnit.SECONDS), "bus setup prevented the fallback from starting")
                if (!disposeDuringAcquire) controller.setEnabled(false)
                controller.close()
                val worker = acquireWorker.get(1, TimeUnit.SECONDS)
                worker.join(2000)
                assertFalse(worker.isAlive, "disposal did not drain the acquisition worker")
                assertTrue(child.waitFor(2, TimeUnit.SECONDS), "fallback was not released")
                bus.awaitDisconnect()
            } finally {
                controller.close()
            }
        }
    }

    private class StalledBus(authenticate: Boolean) : AutoCloseable {
        private val directory = Files.createTempDirectory("nuvio-stalled-bus-")
        private val socketPath = directory.resolve("bus")
        private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            .bind(UnixDomainSocketAddress.of(socketPath))
        val address = "unix:path=$socketPath"
        private val stalled = CountDownLatch(1)
        private val disconnected = CountDownLatch(1)
        @Volatile private var client: SocketChannel? = null
        @Volatile private var failure: Throwable? = null
        @Volatile private var closed = false
        private val worker = Thread({
            try {
                server.accept().use { connection ->
                    client = connection
                    val input = Channels.newInputStream(connection)
                    fun readLine(): String = buildString {
                        while (true) {
                            val byte = input.read()
                            check(byte >= 0) { "client disconnected before authentication" }
                            if (byte == '\n'.code) break
                            append(byte.toChar())
                        }
                    }.trimEnd('\r')
                    check(readLine().startsWith("\u0000AUTH EXTERNAL"))
                    if (authenticate) {
                        val output = Channels.newOutputStream(connection)
                        output.write("OK 0123456789abcdef0123456789abcdef\r\n".toByteArray())
                        var line = readLine()
                        if (line == "NEGOTIATE_UNIX_FD") {
                            output.write("ERROR Unix FD passing disabled\r\n".toByteArray())
                            line = readLine()
                        }
                        check(line == "BEGIN")
                        check(input.read() >= 0) { "client did not send Hello" }
                    }
                    stalled.countDown()
                    // Keep the socket open, deliberately withholding auth/Hello replies.
                    while (input.read() >= 0) { }
                    disconnected.countDown()
                }
            } catch (error: Throwable) {
                if (!closed) failure = error
                stalled.countDown()
                disconnected.countDown()
            }
        }, "nuvio-test-stalled-bus").apply { isDaemon = true; start() }

        fun awaitStall() {
            assertTrue(stalled.await(2, TimeUnit.SECONDS), "client did not reach the stalled handshake")
            failure?.let { throw AssertionError("mock bus failed", it) }
        }

        fun awaitDisconnect() {
            assertTrue(disconnected.await(2, TimeUnit.SECONDS), "timed-out connection was not closed")
            failure?.let { throw AssertionError("mock bus failed", it) }
        }

        override fun close() {
            closed = true
            client?.close()
            server.close()
            worker.join(2000)
            Files.deleteIfExists(socketPath)
            Files.deleteIfExists(directory)
        }
    }
}
