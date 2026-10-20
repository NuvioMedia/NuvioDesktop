package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.format.formatLocalTime
import com.nuvio.app.features.party.PartyChat
import com.nuvio.app.features.party.PartyChatStatus
import com.nuvio.app.features.party.PartyMember
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.party.partyChatRows
import com.nuvio.app.features.party.behindMs
import com.nuvio.app.features.party.displayTitle
import com.nuvio.app.features.party.formatPartyCode
import com.nuvio.app.features.party.partyInviteLink
import com.nuvio.app.features.party.presenceState
import com.nuvio.app.features.party.rememberProfileAvatar
import com.nuvio.app.features.party.statusRes
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_remove
import nuvio.composeapp.generated.resources.party_behind
import nuvio.composeapp.generated.resources.party_behind_title
import nuvio.composeapp.generated.resources.party_chat_empty_message
import nuvio.composeapp.generated.resources.party_chat_empty_title
import nuvio.composeapp.generated.resources.party_chat_new_messages
import nuvio.composeapp.generated.resources.party_chat_not_sent
import nuvio.composeapp.generated.resources.party_chat_placeholder
import nuvio.composeapp.generated.resources.party_chat_send
import nuvio.composeapp.generated.resources.party_catch_up
import nuvio.composeapp.generated.resources.party_code_label
import nuvio.composeapp.generated.resources.party_copy_invite
import nuvio.composeapp.generated.resources.party_end
import nuvio.composeapp.generated.resources.party_end_message
import nuvio.composeapp.generated.resources.party_end_title
import nuvio.composeapp.generated.resources.party_experimental
import nuvio.composeapp.generated.resources.party_host
import nuvio.composeapp.generated.resources.party_host_only_description
import nuvio.composeapp.generated.resources.party_host_only_note
import nuvio.composeapp.generated.resources.party_host_only_title
import nuvio.composeapp.generated.resources.party_invite_copied
import nuvio.composeapp.generated.resources.party_invite_message
import nuvio.composeapp.generated.resources.party_leave
import nuvio.composeapp.generated.resources.party_now_watching
import nuvio.composeapp.generated.resources.party_open_seat
import nuvio.composeapp.generated.resources.party_reconnecting
import nuvio.composeapp.generated.resources.party_remove_message
import nuvio.composeapp.generated.resources.party_remove_title
import nuvio.composeapp.generated.resources.party_seats
import nuvio.composeapp.generated.resources.party_share_description
import nuvio.composeapp.generated.resources.party_share_title
import nuvio.composeapp.generated.resources.party_share_unavailable
import nuvio.composeapp.generated.resources.party_sign_in_required
import nuvio.composeapp.generated.resources.party_start_action
import nuvio.composeapp.generated.resources.party_start_description
import nuvio.composeapp.generated.resources.party_start_title
import nuvio.composeapp.generated.resources.party_switch
import nuvio.composeapp.generated.resources.party_switch_confirm
import nuvio.composeapp.generated.resources.party_switch_message
import nuvio.composeapp.generated.resources.party_switch_title
import nuvio.composeapp.generated.resources.party_tab_chat
import nuvio.composeapp.generated.resources.party_tab_party
import nuvio.composeapp.generated.resources.party_title
import nuvio.composeapp.generated.resources.party_unavailable
import nuvio.composeapp.generated.resources.party_watch_with_party
import nuvio.composeapp.generated.resources.party_you
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
internal fun PlayerScreenRuntime.rememberPartyControlsState(): PlayerControlPartyState {
    val partyState by PartyRepository.state.collectAsStateWithLifecycle()
    val playback by PartyRepository.playback.collectAsStateWithLifecycle()
    val available by PartyRepository.available.collectAsStateWithLifecycle()
    val notice = rememberPartyNotice()
    val avatar = rememberProfileAvatar()
    val clipboard = LocalClipboardManager.current
    val party = partyState.party
    val invite = party?.let {
        stringResource(Res.string.party_invite_message, formatPartyCode(it.code), partyInviteLink(it.code))
    }
    val copiedLabel = stringResource(Res.string.party_invite_copied)
    SideEffect {
        copyPartyInvite = {
            if (invite != null) {
                clipboard.setText(AnnotatedString(invite))
                showPartyMessage(copiedLabel)
            }
        }
    }
    val chatMessages by PartyChat.messages.collectAsStateWithLifecycle()
    val unread by PartyChat.unread.collectAsStateWithLifecycle()
    val chatVisible = partyChatVisible && party != null
    DisposableEffect(chatVisible) {
        PartyChat.setOpen(chatVisible)
        onDispose { PartyChat.setOpen(false) }
    }
    val canStart = PartyRepository.canUse
    val base = PlayerControlPartyState(
        available = available,
        panelToken = partyPanelToken,
        busy = partyState.busy,
        canStart = canStart,
        startNote = when {
            !available -> stringResource(Res.string.party_unavailable)
            !canStart -> stringResource(Res.string.party_sign_in_required)
            else -> ""
        },
        profile = PlayerControlPartyMember(
            name = avatar.name,
            initial = avatar.name.initial(),
            imageUrl = avatar.imageUrl.orEmpty(),
            color = avatar.color.orEmpty(),
        ),
        message = partyMessage,
        messageToken = partyMessageToken,
        labels = partyControlLabels(),
        chat = PlayerControlChatState(unread = unread),
    )
    if (party == null) return base

    val playerMedia = currentPartyMedia()
    val elsewhere = playerMedia.key != party.media.key
    val selfBehindMs = if (partyBehind) partyBehindMs ?: 0L else null
    val storage = "${ServerConfigurationRepository.active.value.backendUrl}/storage/v1/object/"
    val you = stringResource(Res.string.party_you)
    val members = party.members.map { member ->
        val isSelf = member.id == party.memberId
        val behindMs = if (isSelf) selfBehindMs else party.behindMs(member)
        PlayerControlPartyMember(
            id = member.id,
            name = if (isSelf) "${member.displayName} ($you)" else member.displayName,
            initial = member.displayName.initial(),
            imageUrl = member.avatarUrl?.takeIf { it.startsWith(storage) }.orEmpty(),
            color = member.avatarColor.orEmpty(),
            isHost = member.id == party.hostMemberId,
            status = if (behindMs != null) {
                stringResource(Res.string.party_behind, formatPlaybackTime(behindMs))
            } else {
                stringResource(party.statusRes(member))
            },
            presence = party.presenceState(member).name.lowercase(),
            canRemove = party.isHost && !isSelf,
            removeTitle = stringResource(Res.string.party_remove_title, member.displayName),
        )
    }
    val chat = if (chatVisible) {
        PlayerControlChatState(
            unread = unread,
            ready = true,
            messages = partyChatRows(chatMessages).map { row ->
                val message = row.message
                val member = party.member(message.memberId)
                    ?: PartyMember(
                        id = message.memberId,
                        displayName = message.name,
                        avatarUrl = message.avatarUrl,
                        avatarColor = message.avatarColor,
                    )
                PlayerControlChatMessage(
                    key = message.key,
                    memberId = message.memberId,
                    name = member.displayName,
                    initial = member.displayName.initial(),
                    imageUrl = member.avatarUrl?.takeIf { it.startsWith(storage) }.orEmpty(),
                    color = member.avatarColor.orEmpty(),
                    body = message.body,
                    time = if (row.showTime) formatLocalTime(message.sentAt) else "",
                    mine = message.mine,
                    sent = message.status == PartyChatStatus.Sent,
                    failed = message.status == PartyChatStatus.Failed,
                    startsGroup = row.startsGroup,
                    endsGroup = row.endsGroup,
                    showTime = row.showTime,
                )
            },
        )
    } else {
        PlayerControlChatState(unread = unread)
    }
    val canSwitch = elsewhere && party.isHost
    return base.copy(
        active = true,
        synced = !elsewhere,
        code = party.code,
        connected = party.connected,
        isHost = party.isHost,
        hostOnly = party.hostOnly,
        sharing = party.sharing,
        shareNote = if (party.sharing && currentPartyStream() == null) stringResource(Res.string.party_share_unavailable) else "",
        canEnd = party.isHost && party.members.size > 1,
        seatsLabel = stringResource(Res.string.party_seats, party.members.size, party.maxMembers),
        maxMembers = party.maxMembers,
        members = members,
        behindTitle = selfBehindMs?.let { stringResource(Res.string.party_behind_title, formatPlaybackTime(it)) }.orEmpty(),
        nowWatching = if (elsewhere) stringResource(Res.string.party_now_watching, party.media.displayTitle()) else "",
        switchLabel = if (canSwitch) stringResource(Res.string.party_switch, playerMedia.displayTitle()) else "",
        switchMessage = if (canSwitch) stringResource(Res.string.party_switch_message, playerMedia.displayTitle()) else "",
        pillMessage = partyPillMessage(party, playback, notice).orEmpty(),
        pillPinned = partyPillPinned(party, playback, notice),
        chat = chat,
    )
}

@OptIn(ExperimentalEncodingApi::class)
internal fun PlayerScreenRuntime.handlePartyControlsEvent(type: String, value: Double): Boolean {
    if (type.startsWith(CHAT_SEND_EVENT)) {
        val text = runCatching {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(type.removePrefix(CHAT_SEND_EVENT))
                .decodeToString()
        }.getOrNull()
        if (text != null) PartyChat.send(text)
        return true
    }
    if (type.startsWith(CHAT_RETRY_EVENT)) {
        PartyChat.retry(type.removePrefix(CHAT_RETRY_EVENT))
        return true
    }
    if (type.startsWith(REMOVE_MEMBER_EVENT)) {
        val memberId = type.removePrefix(REMOVE_MEMBER_EVENT)
        val party = PartyRepository.state.value.party
        if (party != null && party.isHost && memberId != party.memberId && party.members.any { it.id == memberId }) {
            PartyRepository.removeMember(memberId)
        }
        return true
    }
    when (type) {
        "partyStart" -> startParty()
        "partyCopyInvite" -> copyPartyInvite()
        "partyHostOnly" -> PartyRepository.setHostOnly(value >= 0.5)
        "partySharing" -> PartyRepository.setSharing(value >= 0.5)
        "partyWatch" -> watchWithParty()
        "partySwitch" -> switchPartyMedia()
        "partyCatchUp" -> catchUpWithParty()
        "partyLeave" -> leaveParty()
        "partyEnd" -> endParty()
        "partyChatVisible" -> partyChatVisible = value >= 0.5
        else -> return false
    }
    return true
}

@Composable
private fun partyControlLabels(): Map<String, String> = mapOf(
    "title" to stringResource(Res.string.party_title),
    "close" to stringResource(Res.string.action_close),
    "cancel" to stringResource(Res.string.action_cancel),
    "startTitle" to stringResource(Res.string.party_start_title),
    "startDescription" to stringResource(Res.string.party_start_description),
    "startAction" to stringResource(Res.string.party_start_action),
    "experimental" to stringResource(Res.string.party_experimental),
    "codeLabel" to stringResource(Res.string.party_code_label),
    "copyInvite" to stringResource(Res.string.party_copy_invite),
    "reconnecting" to stringResource(Res.string.party_reconnecting),
    "host" to stringResource(Res.string.party_host),
    "openSeat" to stringResource(Res.string.party_open_seat),
    "remove" to stringResource(Res.string.action_remove),
    "removeMessage" to stringResource(Res.string.party_remove_message),
    "hostOnlyTitle" to stringResource(Res.string.party_host_only_title),
    "hostOnlyDescription" to stringResource(Res.string.party_host_only_description),
    "hostOnlyNote" to stringResource(Res.string.party_host_only_note),
    "shareTitle" to stringResource(Res.string.party_share_title),
    "shareDescription" to stringResource(Res.string.party_share_description),
    "watchWithParty" to stringResource(Res.string.party_watch_with_party),
    "switchTitle" to stringResource(Res.string.party_switch_title),
    "switchConfirm" to stringResource(Res.string.party_switch_confirm),
    "catchUp" to stringResource(Res.string.party_catch_up),
    "leave" to stringResource(Res.string.party_leave),
    "end" to stringResource(Res.string.party_end),
    "endTitle" to stringResource(Res.string.party_end_title),
    "endMessage" to stringResource(Res.string.party_end_message),
    "tabParty" to stringResource(Res.string.party_tab_party),
    "tabChat" to stringResource(Res.string.party_tab_chat),
    "chatEmptyTitle" to stringResource(Res.string.party_chat_empty_title),
    "chatEmptyMessage" to stringResource(Res.string.party_chat_empty_message),
    "chatPlaceholder" to stringResource(Res.string.party_chat_placeholder),
    "chatSend" to stringResource(Res.string.party_chat_send),
    "chatNotSent" to stringResource(Res.string.party_chat_not_sent),
    "chatNewMessages" to stringResource(Res.string.party_chat_new_messages),
)

private fun String.initial(): String = trim().take(1).uppercase()

private const val REMOVE_MEMBER_EVENT = "partyRemoveMember:"
private const val CHAT_SEND_EVENT = "partyChatSend:"
private const val CHAT_RETRY_EVENT = "partyChatRetry:"
