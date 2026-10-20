package com.nuvio.app.features.player

import kotlinx.serialization.Serializable

@Serializable
data class PlayerControlPartyState(
    val available: Boolean = false,
    val active: Boolean = false,
    val synced: Boolean = false,
    val panelToken: Long = 0L,
    val busy: Boolean = false,
    val canStart: Boolean = false,
    val startNote: String = "",
    val profile: PlayerControlPartyMember = PlayerControlPartyMember(),
    val code: String = "",
    val connected: Boolean = false,
    val isHost: Boolean = false,
    val hostOnly: Boolean = false,
    val sharing: Boolean = false,
    val shareNote: String = "",
    val canEnd: Boolean = false,
    val seatsLabel: String = "",
    val maxMembers: Int = 0,
    val members: List<PlayerControlPartyMember> = emptyList(),
    val behindTitle: String = "",
    val nowWatching: String = "",
    val switchLabel: String = "",
    val switchMessage: String = "",
    val pillMessage: String = "",
    val pillPinned: Boolean = false,
    val message: String = "",
    val messageToken: Long = 0L,
    val labels: Map<String, String> = emptyMap(),
    val chat: PlayerControlChatState = PlayerControlChatState(),
)

@Serializable
data class PlayerControlPartyMember(
    val id: String = "",
    val name: String = "",
    val initial: String = "",
    val imageUrl: String = "",
    val color: String = "",
    val isHost: Boolean = false,
    val status: String = "",
    val presence: String = "",
    val canRemove: Boolean = false,
    val removeTitle: String = "",
)

@Serializable
data class PlayerControlChatState(
    val unread: Int = 0,
    val ready: Boolean = false,
    val messages: List<PlayerControlChatMessage> = emptyList(),
)

@Serializable
data class PlayerControlChatMessage(
    val key: String = "",
    val memberId: String = "",
    val name: String = "",
    val initial: String = "",
    val imageUrl: String = "",
    val color: String = "",
    val body: String = "",
    val time: String = "",
    val mine: Boolean = false,
    val sent: Boolean = true,
    val failed: Boolean = false,
    val startsGroup: Boolean = false,
    val endsGroup: Boolean = false,
    val showTime: Boolean = false,
)
