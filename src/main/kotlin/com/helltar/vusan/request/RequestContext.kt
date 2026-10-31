package com.helltar.vusan.request

import com.helltar.vusan.i18n.Language
import java.time.Instant

/**
 * The conversation a turn happens in: its identity, kind, and what it lets the bot post.
 *
 * Messenger vocabulary stops at the adapter: a telegram forum topic and a discord thread both arrive as
 * [threadId], and [type] is never matched on, only shown to the model.
 */
data class ChatContext(
    val id: String,
    val isPrivate: Boolean,
    /** The adapter's name for this kind of chat, e.g. `supergroup_forum`. */
    val type: String = if (isPrivate) "private" else "group",
    /** The sub-conversation of this turn, where the platform has them; `null` is the chat itself. */
    val threadId: String? = null,
    val title: String? = null,
    val username: String? = null,
    val description: String? = null,
    val capabilities: ChatCapabilities = ChatCapabilities.UNRESTRICTED,
)

/**
 * Who sent the message being answered.
 *
 * [isPerson] is the adapter's verdict: every messenger has accounts standing in for many senders (an
 * anonymous admin, a channel, a webhook). Anything that follows somebody across conversations, such as memory
 * and sandbox files, is keyed only on a sender that passes it. Chat-scoped state is safe either way.
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
 * Everything a turn knows about where it came from, built once at ingress and read unchanged by the runner
 * and every tool, so facts such as the sub-conversation or whether the sender is one person are decided in
 * the only layer that can decide them.
 */
data class RequestContext(
    val platform: Platform,
    val chat: ChatContext,
    val sender: SenderContext,
    /**
     * The message being answered; `null` when nothing sent one, as for a scheduled task. Opaque text like
     * every external id: only the issuing adapter reads it back.
     */
    val messageId: String? = null,
    val replyToMessageId: String? = null,
    val attachedFiles: List<AttachedFile> = emptyList(),
    /**
     * The language of canned replies, read from the message with the client's report as fallback; see
     * `Language.ofText`.
     */
    val language: Language = Language.DEFAULT,
    /**
     * Nothing called the bot outright and a classifier judged the message meant for it. That can be wrong, so
     * the turn may end in silence, and is turned away without a word when the conversation has no room for
     * it.
     */
    val ambient: Boolean = false,
    /**
     * When the answered message was written (its last edit), or `null` without one. The turn may start much
     * later, behind the person's previous turn.
     */
    val writtenAt: Instant? = null,
) {

    /**
     * The attachment for tools that take only one: an album's first item. The sandbox and image editing take
     * all of [attachedFiles]; vision looks at this one, and the album block tells the model so.
     */
    val attachedFile: AttachedFile?
        get() = attachedFiles.firstOrNull()

    /** Who this turn is for, qualified: the key for everything that follows them across chats. */
    val user: UserRef
        get() = UserRef(platform, sender.id)

    /** Where this turn is, qualified: the key for everything belonging to the conversation. */
    val chatRef: ChatRef
        get() = ChatRef(platform, chat.id)

    /** Both together: the key for history, the turn lock and `/stop`. */
    val scope: ConversationScope
        get() = ConversationScope(user, chatRef)
}

/**
 * The key for a person's own things on the sandbox server: their home and their published site. A sender
 * without a personal identity gets none, or every anonymous admin and linked channel would share one home and
 * site.
 *
 * The key names the platform too, since two messengers' numbers say nothing about each other; only telegram
 * has one so far, and any other sender gets none rather than a key that could collide. The server only files
 * sandboxes under it and builds nothing public from it.
 */
val RequestContext.personKeyOrNull: String?
    get() = sender.takeIf { it.isPerson && platform == Platform.TELEGRAM }?.let { user.key }
