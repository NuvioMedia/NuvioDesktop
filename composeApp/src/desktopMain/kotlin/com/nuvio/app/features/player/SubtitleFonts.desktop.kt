package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import javax.swing.SwingUtilities
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal actual object SubtitleFontStorage {
    actual fun defaultFamily(): FontFamily = FontFamily.SansSerif

    private fun file(name: String): Path {
        require(name == "fonts.json" || name.matches(Regex("custom-[a-f0-9-]{36}")))
        return DesktopStorage.rootDir.resolve("subtitle-fonts").resolve(name)
    }

    actual fun read(name: String): ByteArray? = file(name).takeIf {
        Files.isRegularFile(it) && Files.size(it) <= MaxSubtitleFontBytes
    }?.let(Files::readAllBytes)

    actual fun write(name: String, bytes: ByteArray) {
        val target = file(name)
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(temporary, bytes)
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
    }

    actual fun remove(name: String) {
        Files.deleteIfExists(file(name))
    }

    actual fun fontFamily(id: String): FontFamily? =
        file(id).takeIf(Files::isRegularFile)?.let { FontFamily(Font(id, Files.readAllBytes(it))) }
}

@Composable
internal actual fun rememberSubtitleFontImporter(onResult: (Result<SubtitleFont>) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latestResult = rememberUpdatedState(onResult)
    return {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var picked: Path? = null
                    SwingUtilities.invokeAndWait {
                        val dialog = FileDialog(null as Frame?, "Import font", FileDialog.LOAD).apply {
                            setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".ttf") || it.endsWith(".otf") } }
                            isVisible = true
                        }
                        picked = dialog.file?.let { Path.of(dialog.directory, it) }
                    }
                    val path = picked ?: return@runCatching null
                    val name = path.fileName.toString()
                    require(Files.size(path) <= MaxSubtitleFontBytes)
                    SubtitleFonts.import(name, Files.readAllBytes(path))
                }
            }
            result.getOrNull()?.let { latestResult.value(Result.success(it)) }
                ?: result.exceptionOrNull()?.let { latestResult.value(Result.failure(it)) }
        }
    }
}

internal object SubtitleFontResolver {
    private val activeDir: Path by lazy { DesktopStorage.cacheDir.resolve("subtitle-font-active") }

    fun mpvFontName(id: String): String = when (id) {
        "serif" -> "serif"
        "mono" -> "monospace"
        "default", "sans" -> "sans-serif"
        else -> activate(id) ?: "sans-serif"
    }

    fun fontsDir(): String = activeDir.takeIf(Files::isDirectory)?.toString().orEmpty()

    private fun activate(id: String): String? {
        val bytes = SubtitleFontStorage.read(id) ?: return null
        val family = familyName(bytes) ?: return null
        Files.createDirectories(activeDir)
        Files.list(activeDir).use { it.forEach(Files::deleteIfExists) }
        Files.write(activeDir.resolve("$id.ttf"), bytes)
        return family
    }

    private fun familyName(bytes: ByteArray): String? = runCatching {
        fun u16(offset: Int) = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
        fun u32(offset: Int) = (u16(offset) shl 16) or u16(offset + 2)
        val tableCount = u16(4)
        val nameTable = (0 until tableCount)
            .map { 12 + it * 16 }
            .first { String(bytes, it, 4, Charsets.US_ASCII) == "name" }
            .let { u32(it + 8) }
        val count = u16(nameTable + 2)
        val strings = nameTable + u16(nameTable + 4)
        val records = (0 until count).map { nameTable + 6 + it * 12 }
        fun read(nameId: Int): String? = records
            .filter { u16(it + 6) == nameId }
            .sortedBy { if (u16(it) == 3) 0 else 1 }
            .firstNotNullOfOrNull { record ->
                val length = u16(record + 8)
                val offset = strings + u16(record + 10)
                val charset = if (u16(record) == 3 || u16(record) == 0) Charsets.UTF_16BE else Charsets.ISO_8859_1
                String(bytes, offset, length, charset).takeIf { it.isNotBlank() }
            }
        read(16) ?: read(1)
    }.getOrNull()
}
