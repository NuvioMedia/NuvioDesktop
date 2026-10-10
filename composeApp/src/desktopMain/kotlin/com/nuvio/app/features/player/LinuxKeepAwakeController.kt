package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class LinuxKeepAwakeController(
    private val acquireScreenSaver: () -> AutoCloseable = { LinuxScreenSaverInhibitor.acquire() },
    private val startSystemdInhibit: () -> Process = {
        ProcessBuilder(
            "systemd-inhibit", "--what=idle:sleep", "--who=Nuvio",
            "--why=Media playback", "--mode=block", "cat",
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    },
) : AutoCloseable {
    private val log = Logger.withTag("LinuxKeepAwake")
    private var executor: ExecutorService? = null
    private var enabled = false
    private var closed = false
    // Accessed only by the worker, including teardown after an in-flight acquire.
    private var screenSaver: AutoCloseable? = null
    private var systemdInhibit: Process? = null

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (closed || enabled == value) return
        enabled = value
        val worker = executor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nuvio-linux-screensaver-inhibit").apply { isDaemon = true }
        }.also { executor = it }
        worker.execute {
            if (value) {
                screenSaver = runCatching(acquireScreenSaver)
                    .onFailure { log.w(it) { "Could not inhibit the desktop screensaver" } }
                    .getOrNull()
                // logind complements session idle inhibition; it does not replace it on KDE.
                systemdInhibit = runCatching(startSystemdInhibit).getOrNull()
            } else {
                release()
            }
        }
    }

    private fun release() {
        runCatching { screenSaver?.close() }
            .onFailure { log.w(it) { "Could not release desktop screensaver inhibition" } }
        screenSaver = null
        systemdInhibit?.let { process ->
            // EOF ends the inhibited command, including if the JVM exits unexpectedly.
            runCatching { process.outputStream.close() }
            if (!runCatching { process.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)) {
                runCatching { process.descendants().use { children -> children.forEach { it.destroyForcibly() } } }
                process.destroyForcibly()
            }
        }
        systemdInhibit = null
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        executor?.let { worker ->
            // Do not cancel an acquire: its resulting connection must be released afterwards.
            worker.execute(::release)
            worker.shutdown()
        }
    }
}
