package com.nuvio.app.core.diagnostics

/**
 * The parts of a HotSpot fatal error log (`hs_err_pid<pid>.log`) that are safe to
 * send with a crash report.
 *
 * The JVM writes this file when the process dies inside native code (the player
 * bridge, libmpv, QuickJS, the windowing system) or inside the JVM itself. Those
 * crashes never reach the Java uncaught exception handler, so the file is the only
 * trace they leave. The raw file also holds environment variables, the full command
 * line, register and memory dumps and absolute paths, so only a filtered summary is
 * kept: the error, the crashing thread's frames, the OS and CPU description and the
 * file names of the loaded native libraries.
 */
internal data class JvmFatalErrorLog(
    /** For example `EXCEPTION_ACCESS_VIOLATION`, `SIGSEGV`, `SIGILL` or `Internal Error`. */
    val errorType: String,
    /** The header line naming the error, without the `#` prefix. */
    val errorLine: String,
    /** For example `C  [libmpv-2.dll+0x1f2a3]`, when the JVM could name it. */
    val problematicFrame: String?,
    val crashingThread: String?,
    /** Frames of the crashing thread, innermost first, as printed by the JVM. */
    val frames: List<String>,
    /** The filtered report that is attached to the crash event. */
    val summary: String,
) {
    /** Where the crash happened, stable across occurrences of the same bug. */
    val crashLocation: String
        get() = problematicFrame?.let { frame ->
            nativeLibraryInFrame.replace(frame) { match ->
                "[${stableNativeLibraryName(match.groupValues[1])}+0x${match.groupValues[2]}]"
            }
        }
            ?: errorLine.substringAfter('(', "").substringBefore(')').ifBlank { null }
            ?: errorType

    /** The native library that contains the problematic frame, if any. */
    val crashLibrary: String?
        get() = problematicFrame
            ?.let { frame -> nativeLibraryInFrame.find(frame)?.groupValues?.get(1) }
            ?.let(::stableNativeLibraryName)

    companion object {
        private val nativeLibraryInFrame = Regex("""\[([^\]+]+)\+0x([0-9a-fA-F]+)]""")
        private val randomDigits = Regex("""\d{8,}""")

        /**
         * Libraries unpacked at run time get a random name, such as
         * `quickjs_quickjs15849965290451525793.tmp` or `jna1416430014348064172.dll`;
         * without this every occurrence of one crash would look like a new one.
         */
        fun stableNativeLibraryName(name: String): String = randomDigits.replace(name, "*")
        private val pathStart = Regex("""(?:[A-Za-z]:\\|/)""")
        private val nativeLibraryName = Regex(
            """.+\.(?:dll|dylib|so|jnilib)(?:\.[0-9][0-9.]*)?""",
            RegexOption.IGNORE_CASE,
        )
        private const val maxFrames = 120
        private const val maxLibraries = 400

        /**
         * Returns null when [text] is not a JVM fatal error log.
         *
         * [homeDir] is replaced by `~` in every kept line so the user's account name
         * does not leave the computer.
         */
        fun parse(text: String, homeDir: String? = null): JvmFatalErrorLog? {
            val lines = text.lineSequence().map { it.trimEnd('\r') }.toList()
            val header = lines.takeWhile { it.startsWith("#") }
            if (header.isEmpty()) return null
            val headerText = header.map { it.removePrefix("#").trim() }

            val errorLine = headerText.firstOrNull { it.contains(", pid=") } ?: return null
            val errorType = errorLine
                .substringBefore(" (")
                .substringBefore(", pid=")
                .trim()
                .ifBlank { return null }
            val problematicFrame = headerText
                .indexOfFirst { it.equals("Problematic frame:", ignoreCase = true) }
                .takeIf { it >= 0 }
                ?.let { headerText.getOrNull(it + 1) }
                ?.ifBlank { null }

            val body = lines.drop(header.size)
            val threadLine = body.firstOrNull { it.startsWith("Current thread") }
            val nativeFrames = body.blockAfter { it.startsWith("Native frames:") }
            val javaFrames = body.blockAfter { it.startsWith("Java frames:") }
            val frames = (nativeFrames + javaFrames).distinct().take(maxFrames)

            val scrub = homeScrubber(homeDir)
            val summary = buildString {
                header.forEach { appendLine(scrub(it)) }
                appendLine()
                body.firstOrNull { it.startsWith("Host:") }?.let { appendLine(scrub(it)) }
                body.firstOrNull { it.startsWith("Time:") }
                    ?.substringAfter("elapsed time:", "")
                    ?.trim()
                    ?.ifBlank { null }
                    ?.let { appendLine("Elapsed time: $it") }
                appendLine()
                threadLine?.let { appendLine(scrub(it)) }
                if (nativeFrames.isNotEmpty()) {
                    appendLine("Native frames:")
                    nativeFrames.take(maxFrames).forEach { appendLine(scrub(it)) }
                }
                if (javaFrames.isNotEmpty()) {
                    appendLine("Java frames:")
                    javaFrames.take(maxFrames).forEach { appendLine(scrub(it)) }
                }
                body.firstOrNull { it.startsWith("siginfo:") }?.let { appendLine(scrub(it)) }
                appendLine()
                val osBlock = body.blockAfter { it == "OS:" }
                if (osBlock.isNotEmpty()) {
                    appendLine("OS:")
                    osBlock.forEach { appendLine(scrub(it)) }
                }
                listOf("CPU:", "Memory:", "vm_info:").forEach { prefix ->
                    body.firstOrNull { it.startsWith(prefix) }?.let { appendLine(scrub(it)) }
                }
                val libraries = body.blockAfter { it.startsWith("Dynamic libraries:") }
                    .mapNotNull(::nativeLibraryFileName)
                    .distinct()
                    .take(maxLibraries)
                if (libraries.isNotEmpty()) {
                    appendLine()
                    appendLine("Loaded native libraries:")
                    libraries.forEach { appendLine(it) }
                }
            }.trimEnd() + "\n"

            return JvmFatalErrorLog(
                errorType = errorType,
                errorLine = scrub(errorLine),
                problematicFrame = problematicFrame?.let(scrub),
                crashingThread = threadLine?.let(::threadName)?.let(scrub),
                frames = frames.map(scrub),
                summary = summary,
            )
        }

        /** The lines after the first line matching [start], up to the next blank line. */
        private fun List<String>.blockAfter(start: (String) -> Boolean): List<String> {
            val index = indexOfFirst(start)
            if (index < 0) return emptyList()
            return drop(index + 1).takeWhile { it.isNotBlank() }
        }

        private fun threadName(line: String): String? {
            val quoted = line.substringAfter('"', "").substringBefore('"', "")
            if (quoted.isNotBlank()) return quoted
            return line.substringAfter("):", "").trim().substringBefore(' ').ifBlank { null }
        }

        private fun nativeLibraryFileName(line: String): String? {
            val start = pathStart.find(line)?.range?.first ?: return null
            val path = line.substring(start).removeSuffix(" (deleted)").trim()
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            return name.takeIf { nativeLibraryName.matches(it) }
        }

        private fun homeScrubber(homeDir: String?): (String) -> String {
            val home = homeDir?.trimEnd('/', '\\')?.takeIf { it.length > 1 } ?: return { it }
            val variants = listOf(home, home.replace('\\', '/'), home.replace('/', '\\')).distinct()
            return { line ->
                variants.fold(line) { current, variant ->
                    current.replace(variant, "~", ignoreCase = true)
                }
            }
        }
    }
}
