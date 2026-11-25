package com.nuvio.app.features.discordrpc

import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.AppPresenceState
import com.nuvio.app.core.ui.PresenceSnapshot
import com.nuvio.app.features.settings.DiscordRichPresenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

internal class DiscordDisconnected : Exception()

// Publishes each distinct presence update. Throws DiscordDisconnected when the connection drops.
internal suspend fun DiscordIpcClient.publishPresence(
    snapshots: Flow<PresenceSnapshot?>,
    onPublished: (DiscordActivity) -> Unit,
) {
    var last: DiscordActivity? = null
    snapshots.collect { snapshot ->
        val activity = snapshot?.toDiscordActivity() ?: IdleActivity
        if (activity == last) return@collect
        val result = publishActivity(activity)
        if (result == SetActivityResult.Disconnected) throw DiscordDisconnected()
        // Remember it even when refused, so the same payload is not re-sent on every update.
        last = activity
        onPublished(activity)
    }
}

private const val ReconnectDelayMs = 15_000L

// Discord activity type: 0 = Playing, 2 = Listening, 3 = Watching, 5 = Competing.
// Nuvio is a media app, so every presence it publishes is a Watching one -- including the menus,
// where Discord renders "Watching Nuvio" instead of the default "Playing Nuvio" (a game).
private const val WatchingActivityType = 3

internal object DiscordPresenceManager {
    private val log = Logger.withTag("DiscordPresenceManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = DiscordIpcClient(DiscordConfig.CLIENT_ID)
    private var syncJob: Job? = null
    private var lastActivity: DiscordActivity? = null

    fun start() {
        if (DiscordConfig.CLIENT_ID.isBlank()) return
        DiscordRichPresenceRepository.ensureLoaded()
        scope.launch {
            DiscordRichPresenceRepository.enabled.collectLatest { enabled ->
                if (enabled) startSync() else stopSync()
            }
        }
    }

    fun shutdown() {
        runBlocking { stopSync() }
    }

    private suspend fun startSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                val connected = client.connect()
                if (connected) {
                    lastActivity = null
                    try {
                        client.publishPresence(AppPresenceState.current) { lastActivity = it }
                    } catch (e: DiscordDisconnected) {
                        log.d { "Discord IPC disconnected, retrying" }
                    }
                }
                delay(ReconnectDelayMs)
            }
        }
    }

    private suspend fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        if (lastActivity != null) client.setActivity(null)
        delay(300L)
        lastActivity = null
        client.disconnect()
    }
}

// Shown when nothing has been published yet, so the profile still reads "Watching Nuvio".
private val IdleActivity = DiscordActivity(
    type = WatchingActivityType,
    details = "Browsing Nuvio",
)

private fun String.toDiscordEpisodeLabel(): String {
    val match = Regex("""S(\d+)E(\d+)(?:\s*-\s*(.*))?""").matchEntire(trim())
        ?: return this
    val season = match.groupValues[1]
    val episode = match.groupValues[2]
    val title = match.groupValues.getOrNull(3).orEmpty().trim()
    return if (title.isBlank()) "S$season, E$episode" else "S$season, E$episode: $title"
}


// Discord refuses the whole activity (evt=ERROR) when a text field is outside 2..128 characters
// or an image key/URL is longer than 300 characters (SET_ACTIVITY schema of discord/embedded-app-sdk).
private const val MaxActivityTextLength = 128
private const val MaxActivityImageLength = 300
private const val PausedSuffix = " • Paused"

internal fun String?.toDiscordActivityText(maxLength: Int = MaxActivityTextLength): String? {
    val text = this?.trim().orEmpty()
    if (text.isEmpty()) return null
    val clamped = if (text.length > maxLength) {
        // Cut on a code point boundary: a split surrogate pair would be sent as "?".
        val cut = text.take(maxLength - 1).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
        cut.trimEnd() + "…"
    } else {
        text
    }
    return clamped.padEnd(2)
}

internal fun String?.toDiscordActivityImage(): String? =
    this?.trim()?.takeIf { url ->
        url.length <= MaxActivityImageLength &&
            (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))
    }

// Fallback for an activity Discord refused: drops the fields clients disagree on (custom name, artwork).
private fun DiscordActivity.withoutOptionalFields(): DiscordActivity = copy(name = null, assets = null)

// Keeps the presence in step with the app: when Discord refuses the activity, retry once with only the plain text fields.
internal suspend fun DiscordIpcClient.publishActivity(activity: DiscordActivity): SetActivityResult {
    val result = setActivity(activity)
    if (result != SetActivityResult.Rejected) return result
    return setActivity(activity.withoutOptionalFields())
}

internal fun PresenceSnapshot.toDiscordActivity(): DiscordActivity = when (this) {
    is PresenceSnapshot.Tab -> DiscordActivity(type = WatchingActivityType, details = "Browsing ${tab.name}")
    is PresenceSnapshot.Details -> DiscordActivity(type = WatchingActivityType, details = "Viewing $title".toDiscordActivityText())
    is PresenceSnapshot.Player -> {
        val episode = episodeLabel?.toDiscordEpisodeLabel()
        val displayTitle = title.toDiscordActivityText()
        // Discord expects Unix timestamps in seconds, not milliseconds.
        val startSecs = (System.currentTimeMillis() - positionMs) / 1_000L
        DiscordActivity(
            type = WatchingActivityType,
            name = displayTitle, // Show the media title under the pseudo when the client honors it.
            details = displayTitle, // Always keep the title here as a fallback for clients that ignore `name`.
            state = if (isPlaying) {
                episode.toDiscordActivityText()
            } else {
                // Shorten the episode first so a long label cannot cut the suffix and look like playback.
                episode.toDiscordActivityText(MaxActivityTextLength - PausedSuffix.length)
                    ?.let { "${it.trimEnd()}$PausedSuffix" } ?: "Paused"
            },
            timestamps = if (isPlaying) {
                // start + end -> Discord renders a live progress bar with time remaining.
                DiscordActivityTimestamps(
                    start = startSecs,
                    end = if (durationMs > 0L) startSecs + durationMs / 1_000L else null,
                )
            } else {
                null
            },
            assets = posterUrl.toDiscordActivityImage()?.let { DiscordActivityAssets(largeImage = it, largeText = displayTitle) },
        )
    }
}
