package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCachePruneTest {
    @Test
    fun removesEveryVersionDirectoryExceptTheInstalledOne() = withTempRoot { root ->
        val current = Files.createDirectories(root.resolve("current-version"))
        val previous = Files.createDirectories(root.resolve("previous-version"))
        Files.writeString(previous.resolve("libplayer_bridge.dll"), "stale payload")

        pruneVersionDirectories(root, current)

        assertTrue(Files.isDirectory(current))
        assertFalse(Files.exists(previous))
    }

    @Test
    fun leavesFilesInsideTheNamespaceAlone() = withTempRoot { root ->
        val current = Files.createDirectories(root.resolve("current-version"))
        val stray = Files.writeString(root.resolve("notes.txt"), "keep")

        pruneVersionDirectories(root, current)

        assertTrue(Files.isDirectory(current))
        assertTrue(Files.exists(stray))
    }

    @Test
    fun doesNothingWhenTheNamespaceDoesNotExist() = withTempRoot { root ->
        val missing = root.resolve("missing")

        pruneVersionDirectories(missing, missing.resolve("current-version"))

        assertFalse(Files.exists(missing))
    }

    @Test
    fun ignoresAVersionDirectoryOutsideTheNamespace() = withTempRoot { root ->
        val namespace = Files.createDirectories(root.resolve("namespace"))
        val installed = Files.createDirectories(namespace.resolve("installed-version"))
        val outside = Files.createDirectories(root.resolve("outside"))

        pruneVersionDirectories(namespace, outside)

        assertTrue(Files.isDirectory(installed))
    }

    private fun withTempRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("nuvio-cache-prune-test")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
