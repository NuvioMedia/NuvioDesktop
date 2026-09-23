package com.nuvio.app.features.discordrpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DiscordActivity(
    // Discord activity type: 0 = Playing, 2 = Listening, 3 = Watching, 5 = Competing.
    // Sending 3 makes Discord show "Watching …" instead of the default "Playing …".
    val type: Int = 0,
    // Top line rendered under the username ("<verb> <name>"). When omitted, Discord uses the
    // name of the application registered to the client id ("Nuvio"). Recent Discord clients honor
    // a custom name here so the media title shows directly under the pseudo; older clients ignore
    // it and fall back to the app name (that is why the title is also kept in `details`).
    val name: String? = null,
    val details: String? = null,
    val state: String? = null,
    val timestamps: DiscordActivityTimestamps? = null,
    val assets: DiscordActivityAssets? = null,
)

@Serializable
internal data class DiscordActivityTimestamps(
    val start: Long? = null,
    // When both start and end are set, Discord renders a live progress bar with time remaining.
    val end: Long? = null,
)

@Serializable
internal data class DiscordActivityAssets(
    @SerialName("large_image") val largeImage: String? = null,
    @SerialName("large_text") val largeText: String? = null,
)
