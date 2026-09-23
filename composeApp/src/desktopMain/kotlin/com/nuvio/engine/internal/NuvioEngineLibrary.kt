package com.nuvio.engine.internal

import java.io.File
import java.util.Locale

public object NuvioEngineLibrary {
    public const val LIBRARY_FILE_NAME: String = "nuvio_engine.dll"

    @Volatile
    private var loadedFrom: File? = null
    private var extractedResource: File? = null

    val isAvailable: Boolean
        get() {
            val osName = (System.getProperty("os.name") ?: "").lowercase(Locale.ROOT)
            return osName.contains("win") && resolve() != null
        }

    public fun resolve(): File? {
        loadedFrom?.let { return it }
        configuredPath()?.takeIf { it.isFile }?.let { return it }
        candidates().firstOrNull { it.isFile }?.let { return it }

        // Fallback: extract bundled DLL from classpath resources if running from a JAR
        extractedResource?.takeIf { it.isFile }?.let { return it }
        val stream = NuvioEngineLibrary::class.java.getResourceAsStream("/native/windows/$LIBRARY_FILE_NAME")
            ?: NuvioEngineLibrary::class.java.getResourceAsStream("/$LIBRARY_FILE_NAME")
        if (stream != null) {
            try {
                val tempDir = File(System.getProperty("java.io.tmpdir"), "nuvio-engine")
                tempDir.mkdirs()
                val targetFile = File(tempDir, LIBRARY_FILE_NAME)
                stream.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                extractedResource = targetFile
                return targetFile
            } catch (_: Throwable) {
                // ignore extraction failure and return null
            }
        }
        return null
    }

    @Synchronized
    public fun load() {
        if (loadedFrom != null) return
        val library = resolve()
            ?: throw UnsatisfiedLinkError(
                "nuvio_engine.dll was not found beside the app runtime or under build/native/windows; " +
                    "run :composeApp:buildWindowsNuvioEngine or pass -Dnuvio.engine.library=<path>"
            )
        System.load(library.absolutePath)
        loadedFrom = library
    }

    private fun configuredPath(): File? {
        val configured = System.getProperty("nuvio.engine.library")?.takeIf { it.isNotBlank() }
            ?: System.getenv("NUVIO_ENGINE_LIBRARY")?.takeIf { it.isNotBlank() }
        return configured?.let { File(it) }
    }

    private fun candidates(): List<File> {
        val packaged = System.getProperty("java.home")?.takeIf { it.isNotBlank() }?.let {
            File(it).parentFile?.resolve(LIBRARY_FILE_NAME)
        }
        return listOfNotNull(
            packaged,
            File("composeApp/build/native/windows/$LIBRARY_FILE_NAME"),
            File("build/native/windows/$LIBRARY_FILE_NAME"),
        )
    }
}
