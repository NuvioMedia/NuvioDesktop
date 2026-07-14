package com.nuvio.app.core.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.Locale
import java.util.Properties
import kotlin.io.path.exists

internal object DesktopStorage {
    private val json = Json { ignoreUnknownKeys = true }
    private val stores = mutableMapOf<String, Store>()

    val rootDir: Path by lazy {
        resolveAppDataDir().also { Files.createDirectories(it) }
    }

    fun store(name: String): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(rootDir.resolve("$name.properties")) }
    }

    fun wipe() {
        synchronized(stores) {
            stores.values.forEach(Store::clearInMemory)
            stores.clear()
        }
        if (!rootDir.exists()) return
        Files.walk(rootDir).use { stream ->
            stream
                .sorted(Comparator.reverseOrder())
                .filter { it != rootDir }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    private fun resolveAppDataDir(): Path {
        resolvePortableRoot()?.let { return it.resolve(PORTABLE_DATA_DIR_NAME) }

        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        return when {
            osName.contains("mac") -> userHome.resolve("Library/Application Support/Nuvio")
            osName.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                (appData?.let(Paths::get) ?: userHome.resolve("AppData/Roaming")).resolve("Nuvio")
            }
            else -> {
                val xdgConfig = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                (xdgConfig?.let(Paths::get) ?: userHome.resolve(".config")).resolve("nuvio")
            }
        }
    }

    /**
     * Portable mode: keep every byte Nuvio writes (preferences, downloads, torrserver,
     * updates, caches, temp) inside a folder on the removable drive instead of the host's
     * user profile. Returns the portable root, or null to fall back to the per-OS location.
     *
     * Activation, highest priority first:
     *  1. `-Dnuvio.portable.dir=<path>` or the `NUVIO_PORTABLE_DIR` environment variable.
     *  2. A `nuvio-portable` marker file found next to the launcher (walking up a few
     *     levels). Drop an empty file named `nuvio-portable` beside `Nuvio.exe` on the USB
     *     stick and the app stores its data in `NuvioData/` right there.
     */
    private fun resolvePortableRoot(): Path? {
        (System.getProperty("nuvio.portable.dir") ?: System.getenv("NUVIO_PORTABLE_DIR"))
            ?.takeIf { it.isNotBlank() }
            ?.let { return Paths.get(it) }

        // jpackage sets this to the launched native executable (e.g. ...\Nuvio\Nuvio.exe);
        // fall back to the code source location when running from a plain jar / from source.
        val launcherDir = (System.getProperty("jpackage.app-path")?.let(Paths::get)?.parent)
            ?: runCatching {
                Paths.get(javaClass.protectionDomain.codeSource.location.toURI())
            }.getOrNull()?.let { if (Files.isDirectory(it)) it else it.parent }
            ?: return null

        var dir: Path? = launcherDir
        repeat(PORTABLE_MARKER_MAX_DEPTH) {
            val current = dir ?: return null
            if (Files.exists(current.resolve(PORTABLE_MARKER_NAME))) return current
            dir = current.parent
        }
        return null
    }

    private const val PORTABLE_MARKER_NAME = "nuvio-portable"
    private const val PORTABLE_DATA_DIR_NAME = "NuvioData"
    private const val PORTABLE_MARKER_MAX_DEPTH = 6

    internal class Store(
        private val file: Path,
    ) {
        private val lock = Any()
        private val properties = Properties()
        private var loaded = false

        fun contains(key: String): Boolean = synchronized(lock) {
            ensureLoaded()
            properties.containsKey(key)
        }

        fun getString(key: String): String? = synchronized(lock) {
            ensureLoaded()
            properties.getProperty(key)
        }

        fun putString(key: String, value: String?) = synchronized(lock) {
            ensureLoaded()
            if (value == null) {
                properties.remove(key)
            } else {
                properties.setProperty(key, value)
            }
            persist()
        }

        fun getBoolean(key: String): Boolean? =
            getString(key)?.toBooleanStrictOrNull()

        fun putBoolean(key: String, value: Boolean) {
            putString(key, value.toString())
        }

        fun getInt(key: String): Int? =
            getString(key)?.toIntOrNull()

        fun putInt(key: String, value: Int) {
            putString(key, value.toString())
        }

        fun getFloat(key: String): Float? =
            getString(key)?.toFloatOrNull()

        fun putFloat(key: String, value: Float) {
            putString(key, value.toString())
        }

        fun getStringSet(key: String): Set<String>? =
            getString(key)?.let { payload ->
                runCatching { json.decodeFromString<List<String>>(payload).toSet() }.getOrNull()
            }

        fun putStringSet(key: String, values: Set<String>) {
            putString(key, json.encodeToString(values.toList()))
        }

        fun remove(key: String) = synchronized(lock) {
            ensureLoaded()
            properties.remove(key)
            persist()
        }

        fun removeAll(keys: Iterable<String>) = synchronized(lock) {
            ensureLoaded()
            keys.forEach(properties::remove)
            persist()
        }

        fun clearInMemory() = synchronized(lock) {
            properties.clear()
            loaded = false
        }

        private fun ensureLoaded() {
            if (loaded) return
            loaded = true
            properties.clear()
            if (!file.exists()) return
            runCatching {
                Files.newInputStream(file).use { input ->
                    properties.load(input)
                }
            }
        }

        private fun persist() {
            Files.createDirectories(file.parent)
            Files.newOutputStream(file).use { output ->
                properties.store(output, "Nuvio desktop preferences")
            }
        }
    }
}
