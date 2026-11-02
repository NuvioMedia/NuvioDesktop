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
                    val dialog = FileDialog(null as Frame?, "Import font", FileDialog.LOAD).apply {
                        setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".ttf") || it.endsWith(".otf") } }
                        isVisible = true
                    }
                    val name = dialog.file ?: return@runCatching null
                    val path = Path.of(dialog.directory, name)
                    require(Files.size(path) <= MaxSubtitleFontBytes)
                    SubtitleFonts.import(name, Files.readAllBytes(path))
                }
            }
            result.getOrNull()?.let { latestResult.value(Result.success(it)) }
                ?: result.exceptionOrNull()?.let { latestResult.value(Result.failure(it)) }
        }
    }
}
