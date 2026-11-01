package com.nuvio.app.core.storage

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class DesktopStorageTest {
    @Test
    fun unchanged_operations_do_not_rewrite_the_store() {
        val directory = Files.createTempDirectory("desktop-storage-test")
        val file = directory.resolve("preferences.properties")
        try {
            val store = DesktopStorage.Store(file)
            store.putString("key", "value")
            val sentinel = FileTime.fromMillis(1_000L)
            Files.setLastModifiedTime(file, sentinel)

            store.putString("key", "value")
            store.remove("missing")
            store.removeAll(listOf("also-missing"))

            assertEquals(sentinel, Files.getLastModifiedTime(file))

            store.putString("key", "updated")

            assertNotEquals(sentinel, Files.getLastModifiedTime(file))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun saves_swap_the_file_in_without_leaving_a_temp_copy() {
        val directory = Files.createTempDirectory("desktop-storage-test")
        val file = directory.resolve("preferences.properties")
        val temp = directory.resolve("preferences.properties.tmp")
        try {
            val store = DesktopStorage.Store(file)
            store.putString("key", "value")
            store.putString("key", "updated")

            assertFalse(Files.exists(temp))
            assertEquals("updated", readPersistedValue(file, "key"))
        } finally {
            Files.deleteIfExists(temp)
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun a_failed_save_leaves_the_previous_store_readable() {
        val directory = Files.createTempDirectory("desktop-storage-test")
        val file = directory.resolve("preferences.properties")
        val temp = directory.resolve("preferences.properties.tmp")
        try {
            val store = DesktopStorage.Store(file)
            store.putString("key", "value")
            // A directory on the temp path fails the save after the old file is
            // already on disk, which is the closest a unit test gets to the app
            // dying halfway through one.
            Files.createDirectory(temp)

            assertFailsWith<IOException> { store.putString("key", "updated") }

            assertEquals("value", readPersistedValue(file, "key"))
        } finally {
            Files.deleteIfExists(temp)
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    private fun readPersistedValue(file: Path, key: String): String? =
        Properties().apply { Files.newInputStream(file).use { load(it) } }.getProperty(key)
}
