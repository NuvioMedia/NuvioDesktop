package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.autosync.AutoSyncSubtitleHttp
import com.nuvio.app.features.player.PlayerSubtitleSelection
import com.nuvio.app.features.player.SubtitleSlot
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.isBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Owns only tracks added by a slot. Stream-provided subtitle tracks are never removed here. */
internal class DesktopSubtitleSlots(
    private val lifecycleLock: Any,
    private val currentHandle: () -> Long,
    private val readTracks: (Long) -> List<SubtitleTrack>,
    private val readProperty: (Long, String) -> String = NativePlayerBridge::stringProperty,
    private val writeProperty: (Long, String, String) -> Unit = NativePlayerBridge::setStringProperty,
    private val command: (Long, Array<String>) -> Unit = NativePlayerBridge::subtitleCommand,
) {
    private data class OwnedTrack(val handle: Long, val id: String, val url: String, val file: File?)
    private val ownedTracks = mutableMapOf<SubtitleSlot, OwnedTrack>()
    private var lastPositionInputs: List<String>? = null
    private var overlayHandle = 0L
    private var overlayFont: Pair<String, String>? = null
    private var savedOsdStyle = emptyMap<String, String>()
    private var savedVisibility = emptyMap<String, String>()
    private val styleOptions = listOf("font", "font-size", "bold", "italic", "color", "outline-color", "outline-size",
        "back-color", "shadow-color", "shadow-offset", "border-style", "fonts-dir", "margin-x")
    var enabled = false
        private set

    fun setEnabled(value: Boolean) = synchronized(lifecycleLock) {
        enabled = value
        lastPositionInputs = null
        val handle = currentHandle()
        if (handle == 0L) return@synchronized
        if (!value) {
            if (overlayHandle == handle) {
                command(handle, arrayOf("osd-overlay", "3100", "none", ""))
                savedOsdStyle.forEach { (name, previous) -> writeProperty(handle, name, previous) }
                savedVisibility.forEach { (name, previous) -> writeProperty(handle, name, previous) }
                overlayHandle = 0L
                overlayFont = null
            }
            selectLoaded(handle, SubtitleSlot.Secondary, null, null)
        } else updatePosition()
    }

    /** One ASS event keeps both cues together through explicit and automatic wrapping. */
    fun updatePosition() = synchronized(lifecycleLock) {
        val handle = currentHandle()
        if (!enabled || handle == 0L) return@synchronized
        val primaryId = readProperty(handle, "sid")
        if (readTracks(handle).any { it.id == primaryId && it.isBitmap }) {
            selectLoaded(handle, SubtitleSlot.Primary, null, null)
            return@synchronized
        }
        val style = styleOptions.associateWith { readProperty(handle, "sub-$it") }
        val scale = readProperty(handle, "sub-scale").toDoubleOrNull() ?: 1.0
        val position = readProperty(handle, "sub-pos").toDoubleOrNull() ?: 100.0
        val margin = readProperty(handle, "sub-margin-y").toDoubleOrNull() ?: 34.0
        val width = readProperty(handle, "osd-dimensions/w").toDoubleOrNull()?.takeIf { it > 0 } ?: 1280.0
        val height = readProperty(handle, "osd-dimensions/h").toDoubleOrNull()?.takeIf { it > 0 } ?: 720.0
        val primary = if (primaryId == "no") "" else readProperty(handle, "sub-text")
        val secondary = if (readProperty(handle, "secondary-sid") == "no") "" else readProperty(handle, "secondary-sub-text")
        val inputs = listOf(handle.toString(), primary, secondary, "$scale", "$position", "$margin", "$width", "$height") + style.values
        if (inputs == lastPositionInputs) return@synchronized
        if (overlayHandle != handle) {
            val options = styleOptions.map { "osd-$it" } + listOf("osd-scale", "osd-scale-by-window")
            savedOsdStyle = options.associateWith { readProperty(handle, it) }
            savedVisibility = listOf("sub-visibility", "secondary-sub-visibility").associateWith {
                readProperty(handle, it).ifBlank { "yes" }
            }
            overlayHandle = handle
            overlayFont = null
        }
        writeProperty(handle, "sub-visibility", "no")
        writeProperty(handle, "secondary-sub-visibility", "no")
        writeProperty(handle, "osd-scale", "1")
        writeProperty(handle, "osd-scale-by-window", "yes")
        style.forEach { (name, value) ->
            if (value.isNotEmpty() || name == "fonts-dir") {
                val scaled = if (name in listOf("font-size", "outline-size", "shadow-offset")) {
                    value.toDoubleOrNull()?.let { (it * scale).toString() } ?: value
                } else value
                writeProperty(handle, "osd-$name", scaled)
            }
        }
        val font = style.getValue("font") to style.getValue("fonts-dir")
        if (overlayFont != null && overlayFont != font) {
            // Recreate the libass font provider so newly imported font files are loaded.
            command(handle, arrayOf("osd-overlay", "3100", "none", ""))
        }
        val text = listOf(secondary, primary).filter(String::isNotBlank).joinToString("\\N") { cue ->
            cue.replace("\\", "\\\u2060").replace("{", "\\{").replace("\n", "\\N")
        }
        val resolutionWidth = (720.0 * width / height).toInt().coerceAtLeast(1)
        val bottom = (720.0 - margin) * position / 100.0
        val data = if (text.isEmpty()) "" else "{\\an2\\q0\\pos(${resolutionWidth / 2.0},$bottom)}$text"
        command(handle, arrayOf("osd-overlay", "3100", "ass-events", data, "$resolutionWidth", "720"))
        lastPositionInputs = inputs
        overlayFont = font
    }

    fun owns(handle: Long, trackId: String): Boolean = synchronized(lifecycleLock) {
        ownedTracks.values.any { it.handle == handle && it.id == trackId }
    }

    suspend fun select(slot: SubtitleSlot, selection: PlayerSubtitleSelection?): Boolean {
        val expectedHandle = synchronized(lifecycleLock) { currentHandle() }
        if (expectedHandle == 0L) return false
        var cachedFile: File? = null
        var applied = false
        try {
            if (selection is PlayerSubtitleSelection.External &&
                (selection.url.startsWith("https:", true) || selection.url.startsWith("http:", true))
            ) {
                val response = withTimeout(15_000L) {
                    AutoSyncSubtitleHttp.get(selection.url, selection.headers, 8 * 1024 * 1024, selection.language, requireComplete = true)
                }
                check(response.status in 200..299) { "Subtitle HTTP ${response.status}" }
                withContext(Dispatchers.IO) {
                    val directory = DesktopStorage.cacheDir.resolve("subtitles").toFile().apply { mkdirs() }
                    val extension = selection.url.substringBefore('?').substringAfterLast('.', "srt")
                        .takeIf { it.lowercase() in setOf("srt", "vtt", "ass", "ssa", "sub", "sup") } ?: "srt"
                    val file = File.createTempFile("dual_", ".$extension", directory)
                    cachedFile = file
                    // Preserve bitmap payloads and let libmpv decode the original subtitle bytes.
                    file.writeBytes(response.bodyBytes)
                }
            }
            currentCoroutineContext().ensureActive()
            applied = synchronized(lifecycleLock) {
                if (currentHandle() != expectedHandle || (slot == SubtitleSlot.Secondary && !enabled)) false
                else selectLoaded(expectedHandle, slot, selection, cachedFile)
            }
            return applied
        } finally {
            if (!applied) cachedFile?.delete()
        }
    }

    /** Existing primary AutoSync/restoration calls also obey slot ownership while dual mode is on. */
    fun selectImmediately(slot: SubtitleSlot, selection: PlayerSubtitleSelection?): Boolean = synchronized(lifecycleLock) {
        val handle = currentHandle()
        if (handle == 0L) false else selectLoaded(handle, slot, selection, null)
    }

    private fun selectLoaded(
        handle: Long,
        slot: SubtitleSlot,
        selection: PlayerSubtitleSelection?,
        file: File?,
    ): Boolean {
        val property = if (slot == SubtitleSlot.Primary) "sid" else "secondary-sid"
        val otherProperty = if (slot == SubtitleSlot.Primary) "secondary-sid" else "sid"
        val visibilityProperty = if (slot == SubtitleSlot.Primary) "sub-visibility" else "secondary-sub-visibility"
        val oldVisibility = readProperty(handle, visibilityProperty).ifBlank { "yes" }
        val otherId = readProperty(handle, otherProperty)
        val oldPrimary = readProperty(handle, "sid").ifBlank { "no" }
        val oldSecondary = readProperty(handle, "secondary-sid").ifBlank { "no" }
        val id = when (selection) {
            null -> "no"
            is PlayerSubtitleSelection.BuiltIn -> readTracks(handle).firstOrNull {
                if (selection.trackId != null) it.id == selection.trackId else it.index == selection.index
            }
                ?.takeUnless { (enabled || slot == SubtitleSlot.Secondary) && it.isBitmap }?.id ?: return false
            is PlayerSubtitleSelection.External -> {
                if (ownedTracks.any { (key, track) -> key != slot && track.handle == handle && track.url == selection.url }) {
                    return false
                }
                val previousIds = readTracks(handle).mapTo(mutableSetOf()) { it.id }
                command(handle, arrayOf("sub-add", file?.absolutePath ?: selection.url, "auto"))
                val added = readTracks(handle).firstOrNull { it.id !in previousIds }
                // sub-add auto may select a default track when the primary was Off. Restore both explicitly.
                writeProperty(handle, "sid", oldPrimary)
                writeProperty(handle, "secondary-sid", oldSecondary)
                if (added == null) return false
                if ((enabled || slot == SubtitleSlot.Secondary) && added.isBitmap) {
                    command(handle, arrayOf("sub-remove", added.id))
                    return false
                }
                added.id
            }
        }
        if (id != "no" && id == otherId) return false
        writeProperty(handle, property, id)
        val visibility = if (enabled) "no" else "yes"
        if (id != "no") writeProperty(handle, visibilityProperty, visibility)
        if (readProperty(handle, property) != id || (id != "no" && readProperty(handle, visibilityProperty) != visibility)) {
            writeProperty(handle, property, if (slot == SubtitleSlot.Primary) oldPrimary else oldSecondary)
            writeProperty(handle, visibilityProperty, oldVisibility)
            if (selection is PlayerSubtitleSelection.External) command(handle, arrayOf("sub-remove", id))
            return false
        }
        ownedTracks.remove(slot)?.let { old ->
            if (old.handle == handle && old.id != id && old.id != otherId) {
                command(handle, arrayOf("sub-remove", old.id))
                old.file?.delete()
            }
        }
        if (selection is PlayerSubtitleSelection.External) {
            ownedTracks[slot] = OwnedTrack(handle, id, selection.url, file)
        }
        updatePosition()
        return true
    }

    fun release(handle: Long) = synchronized(lifecycleLock) {
        lastPositionInputs = null
        if (overlayHandle == handle) {
            overlayHandle = 0L
            overlayFont = null
            savedOsdStyle = emptyMap()
            savedVisibility = emptyMap()
        }
        val removed = ownedTracks.filterValues { it.handle == handle }
        removed.forEach { (slot, track) ->
            ownedTracks.remove(slot)
            track.file?.delete()
        }
    }
}
