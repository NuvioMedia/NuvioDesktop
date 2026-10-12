package com.nuvio.app.core.storage

import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DesktopCacheTest {
    @Test
    fun jar_resources_are_installed_once_per_entry_version() {
        val namespace = "test-${UUID.randomUUID()}"
        val workDir = Files.createTempDirectory("desktop-cache-test")
        try {
            val first = byteArrayOf(1, 2, 3)
            val firstUrl = jarResource(workDir.resolve("first.jar"), first)
            val firstDirectory = DesktopCache.installVersionedResources(namespace, mapOf("lib.bin" to firstUrl))
            assertContentEquals(first, Files.readAllBytes(firstDirectory.resolve("lib.bin")))
            assertEquals(firstDirectory, DesktopCache.installVersionedResources(namespace, mapOf("lib.bin" to firstUrl)))

            val second = byteArrayOf(4, 5, 6, 7)
            val secondUrl = jarResource(workDir.resolve("second.jar"), second)
            val secondDirectory = DesktopCache.installVersionedResources(namespace, mapOf("lib.bin" to secondUrl))
            assertNotEquals(firstDirectory, secondDirectory)
            assertContentEquals(second, Files.readAllBytes(secondDirectory.resolve("lib.bin")))
        } finally {
            deleteRecursively(DesktopStorage.cacheDir.resolve(namespace))
            deleteRecursively(workDir)
        }
    }

    @Test
    fun file_resources_are_installed() {
        val namespace = "test-${UUID.randomUUID()}"
        val workDir = Files.createTempDirectory("desktop-cache-test")
        try {
            val source = workDir.resolve("lib.bin")
            Files.write(source, byteArrayOf(9, 8, 7))
            val directory = DesktopCache.installVersionedResources(namespace, mapOf("lib.bin" to source.toUri().toURL()))
            assertContentEquals(byteArrayOf(9, 8, 7), Files.readAllBytes(directory.resolve("lib.bin")))
        } finally {
            deleteRecursively(DesktopStorage.cacheDir.resolve(namespace))
            deleteRecursively(workDir)
        }
    }

    private fun jarResource(jar: Path, bytes: ByteArray): URL {
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("native/lib.bin"))
            output.write(bytes)
            output.closeEntry()
        }
        return URL("jar:${jar.toUri()}!/native/lib.bin")
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
