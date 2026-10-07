package com.nuvio.app.features.p2p

import com.nuvio.app.core.storage.DesktopCache
import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.io.File
import java.util.Locale

internal object DesktopEngineLibrary {
    fun resolve(): File {
        val platform = DesktopHostOs.current
        requireSupportedArchitecture(platform)
        val directory = directoryName(platform)
        val name = libraryName(platform)
        return packagedLibrary(directory, name)
            ?: sourceLibrary(directory, name)
            ?: extractedLibrary(directory, name)
            ?: throw P2pStreamingException("The torrent engine library is not bundled for $directory")
    }

    internal fun libraryName(platform: DesktopHostOs): String = when (platform) {
        DesktopHostOs.MACOS -> "libnuvio_engine.dylib"
        DesktopHostOs.WINDOWS -> "nuvio_engine.dll"
        DesktopHostOs.LINUX -> "libnuvio_engine.so"
        DesktopHostOs.UNKNOWN -> throw unsupportedPlatform()
    }

    internal fun directoryName(platform: DesktopHostOs): String = when (platform) {
        DesktopHostOs.MACOS -> "macos"
        DesktopHostOs.WINDOWS -> "windows"
        DesktopHostOs.LINUX -> "linux"
        DesktopHostOs.UNKNOWN -> throw unsupportedPlatform()
    }

    private fun requireSupportedArchitecture(platform: DesktopHostOs) {
        if (platform == DesktopHostOs.MACOS) return
        val architecture = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
        if (architecture !in setOf("amd64", "x86_64", "x64")) {
            throw P2pStreamingException("P2P streaming is not available for $architecture")
        }
    }

    private fun packagedLibrary(directory: String, name: String): File? =
        System.getProperty("compose.application.resources.dir")
            ?.takeIf(String::isNotBlank)
            ?.let { File(it, "native/$directory/$name") }
            ?.takeIf(File::isFile)

    private fun sourceLibrary(directory: String, name: String): File? =
        listOf(
            File("src/desktopMain/native/$directory/engine/$name"),
            File("composeApp/src/desktopMain/native/$directory/engine/$name"),
        ).firstOrNull(File::isFile)

    private fun extractedLibrary(directory: String, name: String): File? {
        val bytes = DesktopEngineLibrary::class.java
            .getResourceAsStream("/native/$directory/$name")
            ?.use { it.readBytes() }
            ?: return null
        return DesktopCache.installVersionedFiles("nuvio-engine/$directory", mapOf(name to bytes))
            .resolve(name)
            .toFile()
    }

    private fun unsupportedPlatform() =
        P2pStreamingException("P2P streaming is not available on this operating system")
}
