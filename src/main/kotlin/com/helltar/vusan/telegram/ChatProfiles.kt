package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.request.ChatProfile
import com.helltar.vusan.request.ChatProfileLookup
import com.helltar.vusan.request.ChatRef
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import org.telegram.telegrambots.meta.api.objects.ChatPermissions
import org.telegram.telegrambots.meta.api.objects.chat.ChatFullInfo
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberOwner
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberRestricted
import org.telegram.telegrambots.meta.generics.TelegramClient

private val log = KotlinLogging.logger("ChatProfiles")

/**
 * Reads and caches the chat facts a turn cannot get from the message. [TelegramBotRunner] and `TaskScheduler`
 * both need them before the agent runs; otherwise a task pays for a whole run before learning the chat
 * refuses its output.
 *
 * The cache makes that affordable (the description used to cost a `getChat` every turn), and [forget] keeps
 * it honest, since membership changes are announced. Chats that cannot restrict anything skip this, so a
 * private chat costs no call.
 */
class ChatProfiles(
    private val client: TelegramClient,
    private val botId: Long,
    private val ttl: Duration = DEFAULT_TTL,
) : ChatProfileLookup {

    private val cached = ConcurrentHashMap<Long, Cached>()

    override suspend fun of(chat: ChatRef): ChatProfile = of(chat.telegramChatId)

    private data class Cached(val profile: ChatProfile, val readAt: Instant)

    suspend fun of(chatId: Long): ChatProfile {
        cached[chatId]
            ?.takeIf { Instant.now().toEpochMilli() - it.readAt.toEpochMilli() < ttl.inWholeMilliseconds }
            ?.let { return it.profile }

        val profile = read(chatId)
        cached[chatId] = Cached(profile, Instant.now())

        return profile
    }

    /** Drops what was read about a chat, e.g. after telegram announced the bot's rights changed. */
    fun forget(chatId: Long) {
        cached.remove(chatId)
    }

    private suspend fun read(chatId: Long): ChatProfile {
        val chat = call(chatId, "getChat") { executeAsync(GetChat.builder().chatId(chatId).build()) }

        val membership =
            call(chatId, "getChatMember") {
                executeAsync(GetChatMember.builder().chatId(chatId).userId(botId).build())
            }

        return ChatProfile(
            description = chat?.description,
            capabilities = capabilitiesOf(membership, chat),
        )
    }

    // a failed lookup leaves the chat looking unrestricted on purpose: guessing "forbidden" from a blip would
    // strip tools the agent has, and the send fallbacks still cover a real refusal.
    // a refusal that turns out to be real.
    private suspend fun <T> call(chatId: Long, label: String, block: TelegramClient.() -> java.util.concurrent.CompletableFuture<T>): T? =
        runCatching { client.api(block) }
            .onFailure { error ->
                error.rethrowIfCancellation()
                log.debug(error) { "$label failed for chat=$chatId; treating it as unrestricted" }
            }
            .getOrNull()

    private companion object {
        val DEFAULT_TTL = 10.minutes
    }
}

internal fun capabilitiesOf(membership: ChatMember?, chat: ChatFullInfo?): ChatCapabilities {
    val slowModeSeconds = chat?.slowModeDelay ?: 0

    return when {
        // any admin privilege implies `can_manage_chat`, documented as covering "ignore slow mode", and
        // chat-wide permissions never applied to admins.
        membership is ChatMemberOwner -> ChatCapabilities(chatInfo = true, topics = chat?.isForum == true)

        membership is ChatMemberAdministrator ->
            ChatCapabilities(
                chatInfo = membership.canChangeInfo == true,
                topics = chat?.isForum == true && membership.canManageTopics == true,
            )

        // a restriction on the bot itself replaces the chat's defaults rather than adding to them.
        membership is ChatMemberRestricted -> membership.asPermissions().toCapabilities(slowModeSeconds)

        else -> chat?.permissions.toCapabilities(slowModeSeconds)
    }
}

private fun ChatPermissions?.toCapabilities(slowModeSeconds: Int): ChatCapabilities {
    this ?: return ChatCapabilities(slowModeSeconds = slowModeSeconds)

    return ChatCapabilities(
        photos = canSendPhotos == true,
        videos = canSendVideos == true,
        audios = canSendAudios == true,
        documents = canSendDocuments == true,
        voiceNotes = canSendVoiceNotes == true,
        videoNotes = canSendVideoNotes == true,
        polls = canSendPolls == true,
        stickersAndAnimations = canSendOtherMessages == true,
        // the Bot API defines an omitted `can_react_to_messages` as following `can_send_messages`.
        reactions = (canReactToMessages ?: canSendMessages) == true,
        slowModeSeconds = slowModeSeconds,
    )
}

// the chat's defaults and a member's overrides share one permission set, so the member's is normalized onto
// the chat's type.
private fun ChatMemberRestricted.asPermissions(): ChatPermissions =
    ChatPermissions.builder()
        .canSendMessages(canSendMessages)
        .canSendPhotos(canSendPhotos)
        .canSendVideos(canSendVideos)
        .canSendAudios(canSendAudios)
        .canSendDocuments(canSendDocuments)
        .canSendVoiceNotes(canSendVoiceNotes)
        .canSendVideoNotes(canSendVideoNotes)
        .canSendPolls(canSendPolls)
        .canSendOtherMessages(canSendOtherMessages)
        .canReactToMessages(canReactToMessages)
        .build()
