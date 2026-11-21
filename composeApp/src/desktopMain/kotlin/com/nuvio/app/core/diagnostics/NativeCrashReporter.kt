package com.nuvio.app.core.diagnostics

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.settings.SentrySettingsRepository
import io.sentry.Attachment
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Mechanism
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import java.nio.file.Files
import java.util.Date
import kotlin.io.path.getLastModifiedTime

/**
 * Reports crashes that killed the JVM from native code. They skip the Java
 * uncaught exception handler, so Sentry never sees them while the app runs; the
 * next launch finds the JVM's fatal error log and sends a filtered summary.
 */
internal object NativeCrashReporter {
    private const val DESKTOP_MAIN_CLASS = "com.nuvio.app.MainKt"
    private const val ATTACHMENT_NAME = "native-crash.txt"

    private val store by lazy {
        DesktopProcessSessionStore(DesktopStorage.cacheDir.resolve("diagnostics").resolve("sessions"))
    }

    /** Call first in `main`, before any native library loads. */
    fun recordCurrentSession() {
        runCatching {
            if (!SentrySettingsRepository.isSupported) return
            val process = ProcessHandle.current()
            store.record(
                DesktopProcessSession(
                    pid = process.pid(),
                    startedAtMillis = process.info().startInstant().map { it.toEpochMilli() }
                        .orElse(System.currentTimeMillis()),
                    workingDir = System.getProperty("user.dir").orEmpty(),
                    versionName = AppVersionConfig.DESKTOP_VERSION_NAME,
                    versionCode = AppVersionConfig.DESKTOP_VERSION_CODE,
                ),
            )
        }
    }

    /**
     * Sends the fatal error logs of sessions that crashed since the last launch.
     * Sessions that ended are forgotten either way, so a crash is never sent twice
     * and nothing is sent later for crashes that happened while reports were off.
     */
    fun reportEndedSessions(canReport: () -> Boolean) {
        val ended = runCatching {
            store.takeEndedSessions(
                nowMillis = System.currentTimeMillis(),
                isRunning = ::isSessionProcessRunning,
            )
        }.getOrDefault(emptyList())
        ended.forEach { session ->
            if (!canReport()) return
            runCatching { reportIfCrashed(session) }
        }
    }

    private fun reportIfCrashed(session: DesktopProcessSession) {
        val file = findJvmFatalErrorLog(session, temporaryDirectories()) ?: return
        val text = String(Files.readAllBytes(file), Charsets.UTF_8)
        if (!isDesktopAppLog(text)) return
        val log = JvmFatalErrorLog.parse(text, homeDir = System.getProperty("user.home")) ?: return
        val crashedAt = Date(file.getLastModifiedTime().toMillis())
        Sentry.captureEvent(
            nativeCrashEvent(log, session, crashedAt),
            Hint.withAttachment(
                Attachment(log.summary.toByteArray(Charsets.UTF_8), ATTACHMENT_NAME, "text/plain"),
            ),
        )
    }

    private fun isDesktopAppLog(text: String): Boolean =
        text.lineSequence()
            .firstOrNull { it.startsWith("Command Line:") }
            ?.contains(DESKTOP_MAIN_CLASS) == true

    private fun isSessionProcessRunning(session: DesktopProcessSession): Boolean {
        val process = ProcessHandle.of(session.pid).orElse(null) ?: return false
        if (!process.isAlive) return false
        // A process id can be reused; a process that started after the session did
        // is someone else.
        val startedAt = process.info().startInstant().map { it.toEpochMilli() }.orElse(null)
            ?: return true
        return startedAt <= session.startedAtMillis + PROCESS_START_SLACK_MILLIS
    }

    private fun temporaryDirectories(): List<String> {
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        return listOfNotNull(
            System.getProperty("java.io.tmpdir"),
            // HotSpot falls back to /tmp on Linux whatever java.io.tmpdir says.
            "/tmp".takeUnless { isWindows },
        )
    }

    private const val PROCESS_START_SLACK_MILLIS = 1_000L
}

internal fun nativeCrashEvent(
    log: JvmFatalErrorLog,
    session: DesktopProcessSession,
    crashedAt: Date,
    metadata: DesktopSentryMetadata = desktopSentryMetadata(
        osName = System.getProperty("os.name").orEmpty(),
        osArchitecture = System.getProperty("os.arch").orEmpty(),
        versionName = session.versionName,
        versionCode = session.versionCode,
    ),
): SentryEvent = SentryEvent(crashedAt).apply {
    level = SentryLevel.FATAL
    logger = "jvm.fatal_error"
    // The crash belongs to the version that was running, which an update may have
    // replaced since.
    if (session.versionName.isNotBlank()) {
        release = metadata.release
        dist = metadata.distribution
        setTag("app.version_name", session.versionName)
        setTag("app.version_code", session.versionCode.toString())
    }
    setTag("crash.kind", "native")
    log.crashLibrary?.let { setTag("crash.library", it) }
    log.crashingThread?.let { setTag("crash.thread", it) }
    exceptions = listOf(
        SentryException().apply {
            type = log.errorType
            value = log.crashLocation
            mechanism = Mechanism().apply {
                type = "jvm_fatal_error"
                isHandled = false
            }
            stacktrace = SentryStackTrace(
                // Sentry lists the outermost frame first; the JVM prints the crash site first.
                log.frames.map(::nativeCrashStackFrame).reversed(),
            )
        },
    )
    // Group by what crashed and where, not by the caller stack, which varies.
    fingerprints = listOf("jvm-fatal-error", log.errorType, log.crashLocation)
}

private val nativeFrame = Regex("""^\S+\s+\[([^\]+]+)\+0x([0-9a-fA-F]+)](?:\s+(.*))?$""")
private val javaMethod = Regex("""([\w$]+(?:\.[\w$]+)+)\.([\w$<>]+)\(""")
private val appNativeLibraries = listOf("player_bridge", "nuvio_engine")
private const val NO_SOURCE_INFO = "(no source info available)"

internal fun nativeCrashStackFrame(line: String): SentryStackFrame {
    val text = line.substringBefore(NO_SOURCE_INFO).trim()
    val frame = SentryStackFrame()
    val native = nativeFrame.find(text)
    if (native != null) {
        val library = JvmFatalErrorLog.stableNativeLibraryName(native.groupValues[1])
        val symbol = native.groupValues[3].substringBefore("+0x").trim().ifBlank { null }
        frame.`package` = library
        frame.function = symbol ?: "$library+0x${native.groupValues[2]}"
        frame.isNative = true
        frame.isInApp = appNativeLibraries.any { library.contains(it, ignoreCase = true) }
        return frame
    }
    val java = javaMethod.find(text)
    if (java != null) {
        frame.module = java.groupValues[1]
        frame.function = java.groupValues[2]
        frame.isInApp = java.groupValues[1].startsWith("com.nuvio.app.")
        return frame
    }
    // Stubs and frames without a library, such as `C  0x00000132d733ce8b`.
    frame.function = text.substringAfter(' ').trim().ifBlank { text }
    frame.isInApp = false
    return frame
}
