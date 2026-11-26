package com.nuvio.app.core.storage

import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object DesktopCache {
    fun installVersionedFiles(namespace: String, files: Map<String, ByteArray>): Path {
        require(files.isNotEmpty())
        val directory = versionedDirectory(namespace, files)
        files.forEach { (relativePath, bytes) ->
            writeIfChanged(resolveTarget(directory, relativePath), bytes)
        }
        pruneOtherVersions(namespace, directory)
        return directory
    }

    fun installVersionedResources(namespace: String, resources: Map<String, URL>): Path {
        require(resources.isNotEmpty())
        val directory = versionedDirectory(
            namespace,
            resources.mapValues { (_, url) -> resourceStamp(url).toByteArray(Charsets.UTF_8) },
        )
        resources.forEach { (relativePath, url) ->
            val target = resolveTarget(directory, relativePath)
            if (!Files.exists(target)) {
                url.openStream().use { input ->
                    writeAtomically(target) { pending -> Files.copy(input, pending, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
        }
        pruneOtherVersions(namespace, directory)
        return directory
    }

    /**
     * Version directories are content addressed, so every desktop update installs a
     * new one. Without this the previous native player payload (~110 MB) and player
     * UI copies stayed on disk forever. Best effort: files still held open by another
     * instance are skipped, and the leftover directory is retried on the next launch.
     */
    private fun pruneOtherVersions(namespace: String, keep: Path) {
        val cacheDir = DesktopStorage.cacheDir
        val root = cacheDir.resolve(namespace).normalize()
        if (!root.startsWith(cacheDir) || keep.parent != root) return
        pruneVersionDirectories(root, keep)
    }

    private fun versionedDirectory(namespace: String, versionInputs: Map<String, ByteArray>): Path {
        require(namespace.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
        versionInputs.toSortedMap().forEach { (path, bytes) ->
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(bytes)
        }
        val version = digest.digest().joinToString("") { byte -> "%02x".format(byte) }.take(24)
        val directory = DesktopStorage.cacheDir.resolve(namespace).resolve(version).normalize()
        require(directory.startsWith(DesktopStorage.cacheDir))
        return directory
    }

    private fun resolveTarget(directory: Path, relativePath: String): Path {
        val target = directory.resolve(relativePath).normalize()
        require(target.startsWith(directory))
        return target
    }

    private fun resourceStamp(url: URL): String =
        when (val connection = url.openConnection()) {
            is JarURLConnection -> connection.jarEntry.let { entry -> "${entry.size}:${entry.crc}" }
            else -> "${connection.contentLengthLong}:${connection.lastModified}"
        }

    private fun writeIfChanged(target: Path, bytes: ByteArray) {
        if (Files.exists(target) && Files.readAllBytes(target).contentEquals(bytes)) return
        writeAtomically(target) { pending -> Files.write(pending, bytes) }
    }

    private fun writeAtomically(target: Path, write: (Path) -> Unit) {
        Files.createDirectories(target.parent)
        val pending = Files.createTempFile(target.parent, target.fileName.toString(), ".part")
        try {
            write(pending)
            runCatching {
                Files.move(
                    pending,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(pending)
        }
    }
}

internal fun pruneVersionDirectories(root: Path, keep: Path) {
    if (!Files.isDirectory(root)) return
    if (keep.parent != root) return
    val keepName = keep.fileName?.toString() ?: return
    runCatching {
        Files.newDirectoryStream(root).use { entries ->
            for (entry in entries) {
                if (entry.fileName.toString() == keepName) continue
                if (!Files.isDirectory(entry)) continue
                runCatching { entry.toFile().deleteRecursively() }
            }
        }
    }
}
