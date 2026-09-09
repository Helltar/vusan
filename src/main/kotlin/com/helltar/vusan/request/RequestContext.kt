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
    val id: Long,
    val isPrivate: Boolean,
    /** The adapter's name for this flavor of chat, e.g. `supergroup_forum`. */
    val type: String = if (isPrivate) "private" else "group",
    /** The sub-conversation this turn belongs to, where the platform has them; `null` is the chat itself. */
    val threadId: Int? = null,
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
    val id: Long,
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
    val chat: ChatContext,
    val sender: SenderContext,
    /** The message being answered; `null` when nothing sent one, as when a scheduled task fires. */
    val messageId: Long? = null,
    val replyToMessageId: Long? = null,
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
}

/**
 * The key for the services that hold a person's own things — their workspace home, their published
 * site. Both are keyed on the person rather than the chat, and a sender without a personal identity
 * gets neither: one shared account would be one home and one site that every anonymous admin and every
 * linked channel writes into.
 */
val RequestContext.personKeyOrNull: String?
    get() = sender.takeIf { it.isPerson }?.let { "u${it.id}" }
