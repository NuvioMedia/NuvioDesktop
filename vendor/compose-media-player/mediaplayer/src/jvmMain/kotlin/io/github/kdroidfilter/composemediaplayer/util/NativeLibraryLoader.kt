package io.github.kdroidfilter.composemediaplayer.util

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Loads native libraries following a two-stage strategy:
 * 1. Try [System.loadLibrary] (works for packaged apps / GraalVM native-image
 *    where the lib sits on `java.library.path`).
 * 2. Fallback: extract from the classpath (`composemediaplayer/native/<platform>/`)
 *    into a content-addressed persistent cache and load from there.
 */
internal object NativeLibraryLoader {
    private const val RESOURCE_PREFIX = "composemediaplayer/native"
    private val loadedLibraries = mutableSetOf<String>()

    @Synchronized
    fun load(
        libraryName: String,
        callerClass: Class<*>,
    ): Boolean {
        validateNativePathSegment(libraryName)
        if (libraryName in loadedLibraries) return true

        try {
            System.loadLibrary(libraryName)
            loadedLibraries += libraryName
            return true
        } catch (_: UnsatisfiedLinkError) {
            // Not on java.library.path, try classpath extraction.
        }

        val file = extractToCache(libraryName, callerClass) ?: return false
        System.load(file.absolutePath)
        loadedLibraries += libraryName
        return true
    }

    private fun extractToCache(
        libraryName: String,
        callerClass: Class<*>,
    ): File? {
        val platform = detectPlatform()
        val fileName = validateNativePathSegment(mapLibraryName(libraryName))
        val resourcePath = "$RESOURCE_PREFIX/$platform/$fileName"
        val resourceUrl = callerClass.classLoader?.getResource(resourcePath) ?: return null
        val resourceBytes = resourceUrl.openStream().use { it.readBytes() }

        val cacheDir = resolveCacheDir(platform)
        cacheDir.mkdirs()
        val cachedFile = File(cacheDir, contentAddressedNativeFileName(fileName, resourceBytes))
        if (cachedFile.exists() && cachedFile.readBytes().contentEquals(resourceBytes)) {
            return cachedFile
        }

        val tmpFile = Files.createTempFile(cacheDir.toPath(), "$fileName.", ".tmp").toFile()
        try {
            Files.write(tmpFile.toPath(), resourceBytes)
            try {
                Files.move(
                    tmpFile.toPath(),
                    cachedFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tmpFile.toPath(),
                    cachedFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            cachedFile.setExecutable(true)
        } finally {
            tmpFile.delete()
        }

        return cachedFile
    }

    private fun resolveCacheDir(platform: String): File {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        val base =
            when {
                os.contains("win") ->
                    File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"))
                else ->
                    File(System.getProperty("user.home"), ".cache")
            }
        return File(base, "composemediaplayer/native/$platform")
    }

    private fun detectPlatform(): String {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        val arch = System.getProperty("os.arch") ?: ""
        return when {
            os.contains("win") ->
                if (arch.contains("aarch64") || arch.contains("arm")) "win32-arm64" else "win32-x86-64"
            os.contains("linux") ->
                if (arch.contains("aarch64") || arch.contains("arm")) "linux-aarch64" else "linux-x86-64"
            os.contains("mac") ->
                if (arch.contains("aarch64") || arch.contains("arm")) "darwin-aarch64" else "darwin-x86-64"
            else -> "unknown"
        }
    }

    private fun mapLibraryName(name: String): String {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        return when {
            os.contains("win") -> "$name.dll"
            os.contains("mac") -> "lib$name.dylib"
            else -> "lib$name.so"
        }
    }
}

internal fun contentAddressedNativeFileName(
    fileName: String,
    bytes: ByteArray,
): String {
    validateNativePathSegment(fileName)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val extensionIndex = fileName.lastIndexOf('.')
    val stem = if (extensionIndex > 0) fileName.substring(0, extensionIndex) else fileName
    val extension = if (extensionIndex > 0) fileName.substring(extensionIndex) else ""
    return "$stem-$digest$extension"
}

internal fun validateNativePathSegment(value: String): String {
    require(value.isNotBlank()) { "Native library name must not be blank" }
    require(value != "." && value != "..") { "Native library name must not be a dot segment" }
    require(value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) {
        "Native library name must be a single safe path segment"
    }
    return value
}
