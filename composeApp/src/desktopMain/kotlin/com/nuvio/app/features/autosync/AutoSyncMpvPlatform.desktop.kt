package com.nuvio.app.features.autosync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.PlayerControlsState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes retimed subtitles to the app cache and shows AutoSync messages in the player overlay.
 *
 * The desktop player draws its controls in their own overlay above the video, where a toast from
 * the main window is not visible. Messages are therefore added to the overlay's own notification
 * through [withAutoSyncMessage].
 */
internal class DesktopAutoSyncMpvPlatform(
    private val currentNotificationToken: () -> Long,
) : AutoSyncMpvPlatform {

    var message by mutableStateOf<DesktopAutoSyncMessage?>(null)
        private set

    override suspend fun writeSubtitleFile(fileName: String, stalePrefix: String, content: String): String? =
        withContext(Dispatchers.IO) {
            val cacheDir = DesktopStorage.cacheDir.resolve("subtitles").toFile().also { it.mkdirs() }
            val file = File(cacheDir, fileName)
            cacheDir.listFiles { candidate -> candidate.name.startsWith(stalePrefix) && candidate != file }
                ?.forEach { it.delete() }
            file.writeText(content, Charsets.UTF_8)
            file.absolutePath
        }

    override fun showMessage(text: String) {
        message = DesktopAutoSyncMessage(
            text = text,
            sequence = (message?.sequence ?: 0L) + 1L,
            playerTokenWhenShown = currentNotificationToken(),
        )
    }
}

internal data class DesktopAutoSyncMessage(
    val text: String,
    /** Number of AutoSync messages shown so far, so each one changes the notification token. */
    val sequence: Long,
    /** The player's own notification token when this message was shown. */
    val playerTokenWhenShown: Long,
)

/**
 * Adds the last AutoSync message to the overlay notification. The token is the player's token
 * plus the AutoSync message count, so a new message from either side shows; the text is the newer
 * of the two.
 */
internal fun PlayerControlsState.withAutoSyncMessage(message: DesktopAutoSyncMessage?): PlayerControlsState {
    if (message == null) return this
    val autoSyncIsNewer = notificationToken <= message.playerTokenWhenShown
    return copy(
        notificationMessage = if (autoSyncIsNewer) message.text else notificationMessage,
        notificationToken = notificationToken + message.sequence,
    )
}
