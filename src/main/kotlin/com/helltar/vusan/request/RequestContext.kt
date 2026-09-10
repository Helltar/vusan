package com.helltar.vusan.request

import com.helltar.vusan.i18n.Language

/**
 * The conversation a turn is happening in: what identifies it, what it is, and what it currently lets
 * the bot post.
 *
 * A messenger's own vocabulary stops at the adapter. A Telegram forum topic and a Discord thread both
 * arrive here as [threadId], and [type] is never matched on — it is a label the prompt shows the model.
 */
data class ChatContext(
    val id: String,
    val isPrivate: Boolean,
    /** The adapter's name for this flavor of chat, e.g. `supergroup_forum`. */
    val type: String = if (isPrivate) "private" else "group",
    /** The sub-conversation this turn belongs to, where the platform has them; `null` is the chat itself. */
    val threadId: String? = null,
    val title: String? = null,
    val username: String? = null,
    val description: String? = null,
    val capabilities: ChatCapabilities = ChatCapabilities.UNRESTRICTED
)

/**
 * Who sent the message a turn is answering.
 *
 * [isPerson] is the adapter's verdict rather than a guess made here: every messenger has actors that
 * are not one human — an anonymous group admin, a channel posting under its own account, a webhook —
 * and each of those is one account id standing in for many senders. Anything that follows somebody
 * between conversations, their personal memory and their workspace files, is keyed on a sender that
 * passes this and on nothing else. Chat-scoped state is safe either way, since a shared account still
 * cannot reach out of the chat it wrote in.
 */
data class SenderContext(
    val id: String,
    val displayName: String? = null,
    val username: String? = null,
    /** What the sender's client reports, where the platform passes it on; see `Language.fromCode`. */
    val languageCode: String? = null,
    val isPerson: Boolean = true
)

/**
 * Everything a turn knows about where it came from: one record, built once at ingress, reaching the
 * runner and every tool unchanged.
 *
 * Nothing downstream reconstructs it, so a fact decided here — which sub-conversation to answer in,
 * whether the sender is one person — is decided in the only layer that can decide it, and adding a
 * second messenger is one edit rather than one per copy.
 */
data class RequestContext(
    val platform: Platform,
    val chat: ChatContext,
    val sender: SenderContext,
    /**
     * The message being answered; `null` when nothing sent one, as when a scheduled task fires.
     *
     * A message reference is opaque text, like every other external id: a messenger issues it and only
     * that adapter reads it back. Nothing shared compares two of them or expects a number.
     */
    val messageId: String? = null,
    val replyToMessageId: String? = null,
    val attachedFiles: List<AttachedFile> = emptyList(),
    val language: Language = Language.DEFAULT
) {

    /**
     * The attachment a tool means when it can only work on one — an album's first item.
     *
     * Only image editing takes the whole [attachedFiles] list; everything else looks at this one, and
     * the album's own context block tells the model so.
     */
    val attachedFile: AttachedFile?
        get() = attachedFiles.firstOrNull()

    /** Who this turn is for, qualified — the key for everything that follows them between chats. */
    val user: UserRef
        get() = UserRef(platform, sender.id)

    /** Where this turn is, qualified — the key for everything that belongs to the conversation. */
    val chatRef: ChatRef
        get() = ChatRef(platform, chat.id)

    /** Both together: what history, the turn lock and `/stop` are keyed on. */
    val scope: ConversationScope
        get() = ConversationScope(user, chatRef)
}

/**
 * The key for the services that hold a person's own things — their workspace home, their published
 * site. Both are keyed on the person rather than the chat, and a sender without a personal identity
 * gets neither: one shared account would be one home and one site that every anonymous admin and every
 * linked channel writes into.
 *
 * The workspace and site protocols accept `u` plus digits and nothing else, and a site's public
 * address is built from that number, so this cannot simply become [UserRef.key]. Until those services
 * are given a qualified key of their own, only Telegram gets a person key at all — a second platform
 * silently reusing `u<id>` would hand somebody else's home and published site to whoever matched the
 * number. See notes/second-platform.md.
 */
val RequestContext.personKeyOrNull: String?
    get() = sender.takeIf { it.isPerson && platform == Platform.TELEGRAM }?.let { "u${it.id}" }
