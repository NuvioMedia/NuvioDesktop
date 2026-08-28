package com.nuvio.app.features.settings

import com.nuvio.app.features.player.desktop.DesktopHostOs

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

internal object MacAppIconUpdater {
    fun update(icon: AppIconOption): Boolean {
        if (DesktopHostOs.current != DesktopHostOs.MACOS) return false
        return runCatching {
            val appBundle = applicationBundle()
                ?: error("Could not locate the running macOS application bundle")
            val destination = macosBundleIconPath(appBundle)
            val resource = "icons/app-icon-${icon.key}-transparent.icns"
            val input = Thread.currentThread().contextClassLoader.getResourceAsStream(resource)
                ?: error("Missing macOS app icon resource: $resource")
            input.use { iconData ->
                Files.copy(iconData, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            refreshBundleRegistration(appBundle)
        }.onFailure {
            System.err.println("Failed to update the macOS app icon: ${it.message}")
        }.isSuccess
    }

    fun restartAsync() {
        Thread({
            runCatching {
                val appBundle = applicationBundle()
                    ?: error("Could not locate the running macOS application bundle")
                ProcessBuilder(
                    macosRestartCommand(
                        appBundle = appBundle,
                        currentPid = ProcessHandle.current().pid(),
                    ),
                ).start()
                kotlin.system.exitProcess(0)
            }.onFailure {
                System.err.println("Failed to restart the macOS app: ${it.message}")
            }
        }, "Nuvio macOS app restart").apply {
            isDaemon = false
            start()
        }
    }

    private fun applicationBundle(): Path? {
        val candidates = listOfNotNull(
            System.getProperty("compose.application.home"),
            System.getProperty("compose.application.resources.dir"),
        ).mapNotNull { runCatching { Path.of(it) }.getOrNull() }
        return findMacApplicationBundle(candidates)
    }

    private fun refreshBundleRegistration(appBundle: Path) {
        runCatching {
            Files.setLastModifiedTime(appBundle, FileTime.fromMillis(System.currentTimeMillis()))
            val exitCode = ProcessBuilder(LAUNCH_SERVICES_REGISTER, "-f", appBundle.toString())
                .start()
                .waitFor()
            if (exitCode != 0) error("lsregister exited with status $exitCode")
        }.onFailure {
            System.err.println("Failed to refresh the macOS application registration: ${it.message}")
        }
    }

    private const val LAUNCH_SERVICES_REGISTER =
        "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
}

internal fun findMacApplicationBundle(candidates: Iterable<Path>): Path? =
    candidates.firstNotNullOfOrNull { candidate ->
        generateSequence(candidate.toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { path -> path.fileName?.toString()?.endsWith(".app", ignoreCase = true) == true }
    }

internal fun macosBundleIconPath(appBundle: Path): Path =
    appBundle.resolve("Contents/Resources/Nuvio.icns")

internal fun macosRestartCommand(
    appBundle: Path,
    currentPid: Long,
): List<String> = listOf(
    "/bin/sh",
    "-c",
    "while kill -0 \"\$1\" 2>/dev/null; do sleep 0.1; done; exec /usr/bin/open \"\$2\"",
    "nuvio-macos-restart",
    currentPid.toString(),
    appBundle.toString(),
)
