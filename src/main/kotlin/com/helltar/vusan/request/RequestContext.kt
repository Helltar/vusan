package com.helltar.vusan.request

import com.helltar.vusan.i18n.Language
import java.time.Instant

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
    val capabilities: ChatCapabilities = ChatCapabilities.UNRESTRICTED,
)

/**
 * Who sent the message a turn is answering.
 *
 * [isPerson] is the adapter's verdict rather than a guess made here: every messenger has actors that
 * are not one human — an anonymous group admin, a channel posting under its own account, a webhook —
 * and each of those is one account id standing in for many senders. Anything that follows somebody
 * between conversations, their personal memory and their sandbox files, is keyed on a sender that
 * passes this and on nothing else. Chat-scoped state is safe either way, since a shared account still
 * cannot reach out of the chat it wrote in.
 */
data class SenderContext(
    val id: String,
    val displayName: String? = null,
    val username: String? = null,
    /** What the sender's client reports, where the platform passes it on; see `Language.fromCode`. */
    val languageCode: String? = null,
    val isPerson: Boolean = true,
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
    /**
     * The language canned replies to this turn are in. The adapter reads it off the message itself and
     * falls back to what the sender's client reports; see `Language.ofText`.
     */
    val language: Language = Language.DEFAULT,
    /**
     * Nothing called the bot outright — no mention, reply or command — and a classifier judged the
     * message meant for it. That judgment can be wrong, so such a turn may end in silence, and is
     * turned away without a word when the conversation has no room for it.
     */
    val ambient: Boolean = false,
    /**
     * When the sender wrote the message being answered — its last edit, if it was edited — or `null`
     * when nothing sent one. A turn may start long after it, behind the person's previous turn.
     */
    val writtenAt: Instant? = null,
) {

    /**
     * The attachment a tool means when it can only work on one — an album's first item.
     *
     * The sandbox and image editing take the whole [attachedFiles] list; vision looks at this one, and
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
 * The key for the services that hold a person's own things — their sandbox home, their published
 * site. Both are keyed on the person rather than the chat, and a sender without a personal identity
 * gets neither: one shared account would be one home and one site that every anonymous admin and every
 * linked channel writes into.
 *
 * The key names the platform as well as the person, because the numbers of two messengers say nothing
 * about each other: an unqualified `1234` would hand somebody else's home and published site to
 * whoever matched the number. Only Telegram has one so far, and a sender from anywhere else gets none
 * rather than a key that could collide.
 *
 * It travels no further than the sandbox server, which takes it as the alias it files a sandbox under
 * and builds nothing public from it.
 */
val RequestContext.personKeyOrNull: String?
    get() = sender.takeIf { it.isPerson && platform == Platform.TELEGRAM }?.let { "telegram:${it.id}" }
