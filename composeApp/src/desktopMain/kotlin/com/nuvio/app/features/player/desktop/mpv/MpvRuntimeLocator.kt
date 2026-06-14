package com.nuvio.app.features.player.desktop.mpv

import com.nuvio.app.desktop.DesktopRuntimeLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun runtimeLibraryName(): String =
    if (isOsWindows()) "mediampv.dll" else "libmediampv.so"

internal fun isOsWindows(): Boolean =
    System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true

internal fun isOsLinux(): Boolean =
    System.getProperty("os.name")?.lowercase()?.contains("nux") == true

internal data class MpvRuntimeResolution(
    val directory: File?,
    val checkedDirectories: List<String>,
    val diagnostics: String,
) {
    val available: Boolean get() = directory?.resolve(runtimeLibraryName())?.isFile == true
}

internal object MpvRuntimeLocator {
    fun resolve(): MpvRuntimeResolution {
        val candidates = linkedMapOf<String, File>()
        fun add(label: String, file: File?) {
            if (file != null) candidates.putIfAbsent(label, file)
        }

        val resourcesDir = System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        val appDir = resourcesDir?.parentFile
        add("appDir/native", appDir?.resolve("native"))
        add("resourcesDir/native", resourcesDir?.resolve("native"))

        add("env:NUVIO_MEDIAMP_RUNTIME_DIR", System.getenv("NUVIO_MEDIAMP_RUNTIME_DIR")?.toFileOrNull())
        System.getenv("NUVIO_MPV_DIR")?.toFileOrNull()?.let { dir ->
            add("env:NUVIO_MPV_DIR", dir)
            add("env:NUVIO_MPV_DIR/bin", dir.resolve("bin"))
        }
        add("property:nuvio.mediamp.runtime.dir", System.getProperty("nuvio.mediamp.runtime.dir")?.toFileOrNull())
        System.getProperty("nuvio.mpv.dir")?.toFileOrNull()?.let { dir ->
            add("property:nuvio.mpv.dir", dir)
            add("property:nuvio.mpv.dir/bin", dir.resolve("bin"))
        }

        if (isOsLinux()) {
            // Primary mediamp locations
            add("linux:/usr/lib/x86_64-linux-gnu/mediamp", File("/usr/lib/x86_64-linux-gnu/mediamp"))
            add("linux:/usr/local/lib/mediamp", File("/usr/local/lib/mediamp"))
            add("linux:/usr/lib/mediamp", File("/usr/lib/mediamp"))
            add("linux:/usr/lib64/mediamp", File("/usr/lib64/mediamp"))
            
            // Flatpak and Snap environments
            add("linux:${'$'}HOME/.local/share/Nuvio/native", System.getenv("HOME")?.let { File("$it/.local/share/Nuvio/native") })
            add("linux:/var/lib/snapd/snap/nuvio/current/native", File("/var/lib/snapd/snap/nuvio/current/native"))
            
            // AppImage and portable distributions
            add("linux:${'$'}APPDIR/native", System.getenv("APPDIR")?.let { File("$it/native") })
            
            // LD_LIBRARY_PATH for all system libraries
            add("env:LD_LIBRARY_PATH", System.getenv("LD_LIBRARY_PATH")?.toFileOrNull())
        }

        javaLibraryPathEntries().forEach { entry ->
            val dir = File(entry)
            add("java.library.path:${dir.safePath()}", dir)
            add("java.library.path/native:${dir.safePath()}", dir.resolve("native"))
        }

        pathEntries().forEach { entry ->
            add("PATH:${entry.safePath()}", entry)
        }

        if (devLookupEnabled()) {
            System.getProperty("user.dir")?.takeIf { it.isNotBlank() }?.let { userDir ->
                val base = File(userDir)
                add("dev:app/native", base.resolve("app/native"))
                add("dev:native", base.resolve("native"))
                add("dev:mediamp/build-ci", base.resolve("mediamp/mediamp-mpv/build-ci"))
                if (isOsWindows()) {
                    add("dev:mediamp/build-ci/Release", base.resolve("mediamp/mediamp-mpv/build-ci/Release"))
                    add("dev:mediamp/libmpv", base.resolve("mediamp/mediamp-mpv/libmpv/lib/windows/x86_64"))
                }
                if (isOsLinux()) {
                    add("dev:mediamp/build-ci", base.resolve("mediamp/mediamp-mpv/build-ci"))
                }
            }
        }

        val packagedRuntime = if (isOsWindows()) {
            appDir?.resolve("native")
                ?.takeIf { it.resolve(runtimeLibraryName()).isFile && it.resolve("libmpv-2.dll").isFile }
        } else {
            null
        }
        val stremioHybrid = if (packagedRuntime == null && isOsWindows()) {
            resolveStremioHybridRuntime(candidates.values)
        } else {
            null
        }
        stremioHybrid?.let { add("stremio:hybrid-runtime", it) }

        val libName = runtimeLibraryName()
        val checked = candidates.map { (label, dir) ->
            "$label=${dir.safePath()} exists=${dir.isDirectory} $libName=${dir.resolve(libName).isFile}"
        }
        val selected = packagedRuntime
            ?: stremioHybrid?.takeIf { it.resolve(libName).isFile }
            ?: candidates.values.firstOrNull { it.resolve(libName).isFile }
        return MpvRuntimeResolution(
            directory = selected,
            checkedDirectories = checked,
            diagnostics = "selected=${selected?.safePath() ?: "none"} checked=${checked.joinToString(" | ")}",
        )
    }

    private fun resolveStremioHybridRuntime(baseCandidates: Collection<File>): File? {
        val stremioDir = stremioLibmpvDir() ?: return null
        val stremioLibmpv = resolveOrExtractStremioLibmpv(stremioDir) ?: run {
            DesktopRuntimeLog.warn("stremioHybrid: libmpv-2.dll missing dir=${stremioDir.safePath()}")
            return null
        }
        val baseRuntime = baseCandidates.firstOrNull { it.resolve(runtimeLibraryName()).isFile } ?: return null
        val hybridDir = localAppDataDir()
            ?.resolve("Nuvio")
            ?.resolve("cache")
            ?.resolve("mpv-stremio-hybrid")
            ?: return null

        return runCatching {
            Files.createDirectories(hybridDir.toPath())
            baseRuntime.listFiles { file -> file.isFile && file.extension.equals("dll", ignoreCase = true) }
                .orEmpty()
                .forEach { source ->
                    val target = hybridDir.resolve(source.name)
                    if (!target.isFile || target.length() != source.length()) {
                        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            Files.copy(
                stremioLibmpv.toPath(),
                hybridDir.resolve("libmpv-2.dll").toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            DesktopRuntimeLog.info(
                "stremioHybrid: prepared runtime dir=${hybridDir.safePath()} " +
                    "base=${baseRuntime.safePath()} libmpvSize=${stremioLibmpv.length()}",
            )
            hybridDir
        }.onFailure {
            DesktopRuntimeLog.warn("stremioHybrid: prepare failed message=${it.message}")
        }.getOrNull()
    }

    private fun resolveOrExtractStremioLibmpv(stremioDir: File): File? {
        val direct = stremioDir.resolve("libmpv-2.dll")
        if (direct.isFile) return direct

        val rarFile = stremioDir.resolve("libmpv-2.dll.rar")
        if (!rarFile.isFile) return null

        val sevenZip = System.getenv("NUVIO_7Z")?.takeIf { it.isNotBlank() } ?: "7z"
        val outputDir = localAppDataDir()
            ?.resolve("Nuvio")
            ?.resolve("cache")
            ?.resolve("mpv-stremio-extract")
            ?: return null

        return runCatching {
            Files.createDirectories(outputDir.toPath())
            val extracted = outputDir.resolve("libmpv-2.dll")
            if (extracted.isFile && extracted.length() > 0L) return extracted

            DesktopRuntimeLog.info("stremioHybrid: extracting libmpv-2.dll via 7z rar=${rarFile.safePath()}")
            val process = ProcessBuilder(
                sevenZip,
                "x",
                "-y",
                "-o${outputDir.absolutePath}",
                rarFile.absolutePath,
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                DesktopRuntimeLog.warn("stremioHybrid: 7z extract failed exit=$exitCode output=${output.take(500)}")
                return null
            }
            extracted.takeIf { it.isFile }
        }.onFailure {
            DesktopRuntimeLog.warn("stremioHybrid: 7z extract threw message=${it.message}")
        }.getOrNull()
    }

    private fun stremioLibmpvDir(): File? {
        val configured = System.getProperty("nuvio.stremio.libmpv.dir")
            ?: System.getenv("NUVIO_STREMIO_LIBMPV_DIR")
        configured?.toFileOrNull()?.let { return it }

        System.getProperty("user.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.resolve("stremio-community-v5/deps/libmpv/x86_64")
            ?.takeIf { it.isDirectory }
            ?.let { return it }

        return null
    }

    private fun localAppDataDir(): File? =
        System.getenv("LOCALAPPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

    private fun devLookupEnabled(): Boolean =
        System.getenv("NUVIO_DEV_PLAYER_LOOKUP").equals("true", ignoreCase = true) ||
            System.getProperty("nuvio.dev.player.lookup").equals("true", ignoreCase = true)

    private fun javaLibraryPathEntries(): List<String> =
        System.getProperty("java.library.path")
            ?.split(File.pathSeparatorChar)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()

    private fun pathEntries(): List<File> =
        System.getenv("PATH")
            ?.split(File.pathSeparatorChar)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.map(::File)
            .orEmpty()

    private fun String.toFileOrNull(): File? =
        takeIf { it.isNotBlank() }?.let(::File)
}

internal fun File.safePath(): String = DesktopRuntimeLog.safePath(this)
