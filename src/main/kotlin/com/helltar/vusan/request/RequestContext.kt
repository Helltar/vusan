package com.helltar.vusan.request

import com.helltar.vusan.i18n.Language

data class RequestContext(
    val chatId: Long,
    val userId: Long,
    val messageId: Long,
    val replyToMessageId: Long? = null,
    val senderUsername: String? = null,
    val senderDisplayName: String? = null,
    val chatIsPrivate: Boolean = true,
    val attachedFile: AttachedFile? = null,
    val language: Language = Language.DEFAULT,
    val chatCapabilities: ChatCapabilities = ChatCapabilities.UNRESTRICTED
)

// telegram delivers anonymous group admins as GroupAnonymousBot and linked-channel posts as Channel_Bot:
// one account id standing in for many different senders in many chats.
private val SHARED_SENDER_IDS = setOf(1_087_968_824L, 136_817_688L)

/**
 * Whether this sender's id belongs to one person. Anything that follows someone between chats — their
 * personal memory, their workspace files — must be keyed on that and nothing else. Chat-scoped state is
 * safe either way, since a shared account still cannot reach out of the chat it wrote in.
 */
val RequestContext.identifiesOnePerson: Boolean
    get() = userId != 0L && userId !in SHARED_SENDER_IDS

/**
 * The key for the services that hold a person's own things — their workspace home, their published
 * site. Both are keyed on the person rather than the chat, and a sender without a personal identity
 * gets neither: one shared account would be one home and one site that every anonymous admin and every
 * linked channel writes into.
 */
val RequestContext.personKeyOrNull: String?
    get() = takeIf { it.identifiesOnePerson }?.let { "u${it.userId}" }

fun RequestContext.requireUserId(): Long {
    check(userId != 0L) { "User ID is unavailable" }
    return userId
}

fun RequestContext.requireChatId(): Long {
    check(chatId != 0L) { "Chat ID is unavailable" }
    return chatId
}
