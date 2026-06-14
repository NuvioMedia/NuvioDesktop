package com.nuvio.app.desktop

import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.math.min

internal object DesktopRuntimeLog {
    private const val MAX_LOG_BYTES = 200 * 1024L
    private const val TRIM_TO_BYTES = 150 * 1024

    private val processId: Long by lazy { ProcessHandle.current().pid() }
    private val logFile: Path by lazy {
        val localAppData = System.getenv("LOCALAPPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
        localAppData.resolve("Nuvio").resolve("cache").resolve("logs").resolve("desktop-runtime.log")
    }

    @Volatile
    var debugEnabled: Boolean = false

    @Volatile
    private var initialized: Boolean = false

    @Synchronized
    fun initialize(enabled: Boolean = false) {
        debugEnabled = enabled
        initialized = true
        trimExistingLogIfNeeded()
        if (debugEnabled) {
            appendLine("", force = true)
            appendLine("===== Nuvio desktop startup ${Instant.now()} pid=$processId =====", force = true)
        }
    }

    fun installGlobalExceptionHandlers() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            crash("Uncaught exception on thread=${thread.name}", throwable)
            DesktopPlayerRegistry.releaseAll("uncaught:${thread.name}")
        }
        runCatching {
            Toolkit.getDefaultToolkit().systemEventQueue.push(
                object : EventQueue() {
                    override fun dispatchEvent(event: AWTEvent) {
                        try {
                            super.dispatchEvent(event)
                        } catch (throwable: Throwable) {
                            crash("Uncaught AWT/EventQueue exception event=${event.javaClass.name}", throwable)
                            DesktopPlayerRegistry.releaseAll("awtException")
                            throw throwable
                        }
                    }
                },
            )
            info("Installed AWT/EventQueue exception logger")
        }.onFailure {
            crash("Failed to install AWT/EventQueue exception logger", it)
        }
    }

    @Synchronized
    fun debug(message: String) {
        if (debugEnabled) {
            appendLine("${Instant.now()} DEBUG $message")
        }
    }

    @Synchronized
    fun info(message: String) {
        appendLine("${Instant.now()} INFO  $message")
    }

    @Synchronized
    fun warn(message: String) {
        appendLine("${Instant.now()} WARN  $message")
    }

    @Synchronized
    fun error(message: String, throwable: Throwable? = null) {
        appendLine("${Instant.now()} ERROR $message")
        if (throwable != null) {
            appendLine(stackTrace(throwable))
        }
    }

    @Synchronized
    fun crash(message: String, throwable: Throwable? = null) {
        appendLine("${Instant.now()} CRASH $message", force = true)
        if (throwable != null) {
            appendLine(stackTrace(throwable), force = true)
        }
    }

    fun path(): Path = logFile

    fun processPid(): Long = processId

    fun safePath(value: String?): String {
        if (value.isNullOrBlank()) return "unset"
        return runCatching { safePath(File(value)) }.getOrElse { "<local>" }
    }

    fun safePath(file: File?): String {
        if (file == null) return "unset"
        val normalized = file.absoluteFile.path.replace("\\", "/")
        val knownRoots = listOfNotNull(
            System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { "%LOCALAPPDATA%" to it },
            System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { "%APPDATA%" to it },
            System.getenv("TEMP")?.takeIf { it.isNotBlank() }?.let { "%TEMP%" to it },
            System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { "~" to it },
            System.getProperty("user.dir")?.takeIf { it.isNotBlank() }?.let { "\$WORKDIR" to it },
        )

        knownRoots.forEach { (label, root) ->
            val normalizedRoot = File(root).absoluteFile.path.replace("\\", "/").trimEnd('/')
            if (normalized.equals(normalizedRoot, ignoreCase = true)) return label
            if (normalized.startsWith("$normalizedRoot/", ignoreCase = true)) {
                return label + normalized.removePrefixIgnoreCase(normalizedRoot)
            }
        }

        return "<local>/" + normalized
            .split('/')
            .filter { it.isNotBlank() }
            .takeLast(3)
            .joinToString("/")
    }

    fun safePath(path: Path?): String = safePath(path?.toFile())

    fun safePathList(value: String?): String {
        if (value.isNullOrBlank()) return "unset"
        return value.split(File.pathSeparatorChar)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(File.pathSeparator) { safePath(it) }
            .ifBlank { "unset" }
    }

    @Synchronized
    fun logNonDaemonThreads(tag: String, limit: Int = 40) {
        val entries = Thread.getAllStackTraces().keys
            .filter { it.isAlive && !it.isDaemon }
            .sortedBy { it.name }
            .take(limit)
            .joinToString(separator = " | ") { thread ->
                "name=${thread.name},state=${thread.state}"
            }
        appendLine("${Instant.now()} INFO  nonDaemonThreads tag=$tag pid=$processId count=${Thread.getAllStackTraces().keys.count { it.isAlive && !it.isDaemon }} sample=[$entries]")
    }

    private fun appendLine(line: String) {
        appendLine(line, force = false)
    }

    private fun appendLine(line: String, force: Boolean) {
        if (!force && !debugEnabled) return
        ensureLogDirectory()
        trimExistingLogIfNeeded()
        Files.writeString(
            logFile,
            line + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun ensureLogDirectory() {
        if (!initialized) {
            initialized = true
            debugEnabled = false
        }
        Files.createDirectories(logFile.parent)
    }

    private fun trimExistingLogIfNeeded() {
        runCatching {
            if (!Files.exists(logFile) || Files.size(logFile) <= MAX_LOG_BYTES) return
            val bytes = Files.readAllBytes(logFile)
            val keep = min(bytes.size, TRIM_TO_BYTES)
            val retained = bytes.copyOfRange(bytes.size - keep, bytes.size)
            Files.createDirectories(logFile.parent)
            Files.writeString(
                logFile,
                "===== Nuvio desktop log trimmed ${Instant.now()} maxBytes=$MAX_LOG_BYTES =====${System.lineSeparator()}",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            Files.write(logFile, retained, StandardOpenOption.APPEND)
        }
    }

    private fun stackTrace(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
}
