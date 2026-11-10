package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

class DesktopStoragePermissionsTest {
    @Test
    fun store_files_are_readable_by_the_owner_only() {
        assumeTrue("file system has no POSIX permissions", supportsPosixPermissions())

        val directory = Files.createTempDirectory("desktop-storage-permissions")
        val file = directory.resolve("nuvio_debrid_settings.properties")
        try {
            val store = DesktopStorage.Store(file)
            store.putString("debrid_api_key_1", "secret")

            assertEquals("secret", store.getString("debrid_api_key_1"))
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(file),
            )
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun restricting_a_path_without_posix_support_is_a_no_op() {
        val file = Files.createTempFile("desktop-storage-permissions", ".properties")
        try {
            restrictToOwner(file, "rw-------")

            assertEquals("", file.toFile().readText(), "restricting permissions must not touch the contents")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun supportsPosixPermissions(): Boolean =
        Files.getFileAttributeView(
            Path.of(System.getProperty("java.io.tmpdir")),
            java.nio.file.attribute.PosixFileAttributeView::class.java,
        ) != null
}
