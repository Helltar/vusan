package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.request.ChatCapabilities
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

private val log = KotlinLogging.logger("ChatProfile")

/** What a turn needs to know about the chat it answers in, beyond the message that triggered it. */
data class ChatProfile(
    val description: String? = null,
    val capabilities: ChatCapabilities = ChatCapabilities.UNRESTRICTED
) {

    companion object {
        /** Nothing known and nothing restricted — a private chat, or a lookup that was skipped. */
        val NONE = ChatProfile()
    }
}

/**
 * Reads and caches the chat facts a turn cannot get from the message itself. Both callers need them
 * before the agent runs, not after: [TelegramBotRunner] for a live turn and `TaskScheduler` for a
 * scheduled one, which otherwise pays for a whole agent run before finding out the chat refuses what
 * it produced.
 *
 * The cache is what makes that affordable — the description used to cost a `getChat` on every single
 * turn — and [forget] keeps it honest, since a membership change is announced and must not be waited
 * out. Callers skip this entirely for chats that cannot restrict anything, so a private chat costs no
 * call at all.
 */
class ChatProfiles(
    private val client: TelegramClient,
    private val botId: Long,
    private val ttl: Duration = DEFAULT_TTL
) {

    private companion object {
        val DEFAULT_TTL = 10.minutes
    }

    private data class Cached(val profile: ChatProfile, val readAt: Instant)

    private val cached = ConcurrentHashMap<Long, Cached>()

    suspend fun of(chatId: Long): ChatProfile {
        cached[chatId]
            ?.takeIf { Instant.now().toEpochMilli() - it.readAt.toEpochMilli() < ttl.inWholeMilliseconds }
            ?.let { return it.profile }

        val profile = read(chatId)
        cached[chatId] = Cached(profile, Instant.now())

        return profile
    }

    /** Drops what was read about a chat, e.g. after Telegram announced the bot's rights there changed. */
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
            capabilities = capabilitiesOf(membership, chat)
        )
    }

    // a lookup that fails leaves the chat looking unrestricted on purpose: guessing "forbidden" from a
    // network blip would strip the agent of tools it actually has, and the send fallbacks still cover
    // a refusal that turns out to be real.
    private suspend fun <T> call(chatId: Long, label: String, block: TelegramClient.() -> java.util.concurrent.CompletableFuture<T>): T? =
        runCatching { client.api(block) }
            .onFailure { error ->
                error.rethrowIfCancellation()
                log.debug(error) { "$label failed for chat=$chatId; treating it as unrestricted" }
            }
            .getOrNull()
}

internal fun capabilitiesOf(membership: ChatMember?, chat: ChatFullInfo?): ChatCapabilities {
    val slowModeSeconds = chat?.slowModeDelay ?: 0

    return when {
        // any administrator privilege implies `can_manage_chat`, which the Bot API documents as
        // covering "ignore slow mode" — and chat-wide permissions never applied to admins to begin with.
        membership is ChatMemberAdministrator || membership is ChatMemberOwner -> ChatCapabilities.UNRESTRICTED

        // a restriction aimed at the bot itself replaces the chat's defaults rather than adding to them.
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
        slowModeSeconds = slowModeSeconds
    )
}

// the chat's defaults and a restricted member's own overrides carry the same permission set under the
// same names, so the member's version is normalized onto the chat's type and read once.
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
