package com.nuvio.app.core.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** What a later launch needs to find the fatal error log of a process that died. */
internal data class DesktopProcessSession(
    val pid: Long,
    val startedAtMillis: Long,
    val workingDir: String,
    val versionName: String,
    val versionCode: Int,
)

/**
 * One small file per running app process. A file whose process is gone belongs to a
 * session that ended, cleanly or not; the next launch looks for that process's fatal
 * error log and then deletes the file. Several instances can run at once, so every
 * process keeps its own file and live processes are left alone.
 */
internal class DesktopProcessSessionStore(
    private val directory: Path,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
) {
    fun record(session: DesktopProcessSession) {
        Files.createDirectories(directory)
        val properties = Properties().apply {
            setProperty(KEY_PID, session.pid.toString())
            setProperty(KEY_STARTED_AT, session.startedAtMillis.toString())
            setProperty(KEY_WORKING_DIR, session.workingDir)
            setProperty(KEY_VERSION_NAME, session.versionName)
            setProperty(KEY_VERSION_CODE, session.versionCode.toString())
        }
        val target = directory.resolve("${session.pid}-${session.startedAtMillis}$FILE_SUFFIX")
        val temporary = directory.resolve("${target.name}.tmp")
        Files.newOutputStream(temporary).use { properties.store(it, null) }
        // Another launch may read the directory at any moment; it must never see a
        // half-written file, which it would take for an ended session.
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Removes and returns the sessions whose process is no longer running. A file is
     * only returned to the caller that managed to delete it, so two launches at the
     * same moment do not report the same crash twice.
     */
    fun takeEndedSessions(
        nowMillis: Long,
        isRunning: (DesktopProcessSession) -> Boolean,
    ): List<DesktopProcessSession> {
        if (!directory.exists()) return emptyList()
        val entries = runCatching { directory.listDirectoryEntries() }.getOrDefault(emptyList())
        return entries.mapNotNull { file ->
            if (!file.isRegularFile() || !file.name.endsWith(FILE_SUFFIX)) return@mapNotNull null
            val session = runCatching { read(file) }.getOrNull()
            val ended = session == null || !isRunning(session)
            val expired = session != null && nowMillis - session.startedAtMillis > maxAgeMillis
            when {
                ended || expired -> {
                    val claimed = runCatching { Files.deleteIfExists(file) }.getOrDefault(false)
                    session.takeIf { claimed && ended }
                }
                else -> null
            }
        }
    }

    private fun read(file: Path): DesktopProcessSession {
        val properties = Properties()
        Files.newInputStream(file).use(properties::load)
        return DesktopProcessSession(
            pid = properties.getProperty(KEY_PID).toLong(),
            startedAtMillis = properties.getProperty(KEY_STARTED_AT).toLong(),
            workingDir = properties.getProperty(KEY_WORKING_DIR).orEmpty(),
            versionName = properties.getProperty(KEY_VERSION_NAME).orEmpty(),
            versionCode = properties.getProperty(KEY_VERSION_CODE)?.toIntOrNull() ?: 0,
        )
    }

    private companion object {
        const val FILE_SUFFIX = ".session"
        const val KEY_PID = "pid"
        const val KEY_STARTED_AT = "startedAtMillis"
        const val KEY_WORKING_DIR = "workingDir"
        const val KEY_VERSION_NAME = "versionName"
        const val KEY_VERSION_CODE = "versionCode"

        /** Drops files kept alive by a reused process id. */
        const val DEFAULT_MAX_AGE_MILLIS = 14L * 24 * 60 * 60 * 1000
    }
}

/**
 * Finds the fatal error log the JVM wrote for [session], if it crashed.
 *
 * Without `-XX:ErrorFile` HotSpot writes `hs_err_pid<pid>.log` to the process's
 * working directory and, when that is not writable (an install folder, `/` on macOS),
 * to the system temporary directory.
 */
internal fun findJvmFatalErrorLog(
    session: DesktopProcessSession,
    temporaryDirs: List<String>,
    maxBytes: Long = MAX_FATAL_ERROR_LOG_BYTES,
): Path? {
    val fileName = "hs_err_pid${session.pid}.log"
    return (listOf(session.workingDir) + temporaryDirs)
        .filter { it.isNotBlank() }
        .distinct()
        .asSequence()
        .mapNotNull { dir -> runCatching { Path.of(dir).resolve(fileName) }.getOrNull() }
        .firstOrNull { file ->
            runCatching {
                file.isRegularFile() &&
                    file.fileSize() in 1..maxBytes &&
                    // Older logs can carry a reused process id.
                    file.getLastModifiedTime().toMillis() >= session.startedAtMillis - CLOCK_SLACK_MILLIS
            }.getOrDefault(false)
        }
}

private const val MAX_FATAL_ERROR_LOG_BYTES = 8L * 1024 * 1024
private const val CLOCK_SLACK_MILLIS = 2_000L
