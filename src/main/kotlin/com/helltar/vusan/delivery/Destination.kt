package com.helltar.vusan.delivery

import com.helltar.vusan.request.ChatRef

/**
 * Where an answer belongs.
 *
 * A destination, the sub-conversation inside it and the message an answer hangs under are three
 * different things, and only the first is required: a turn nothing sent — a scheduled task firing —
 * has no anchor at all, and never invents one.
 *
 * [threadId] is opaque to everything but the adapter that issued it. A Telegram forum topic and a
 * Discord thread are not the same kind of object, and neither is the conversation: the chat is.
 */
data class Destination(
    val chat: ChatRef,
    val threadId: String? = null,
    val replyToMessageId: Long? = null
) {

    /** The same place, addressed directly rather than as a reply. */
    fun withoutAnchor(): Destination = copy(replyToMessageId = null)
}

/**
 * The line naming who a scheduled answer belongs to, and where it hangs.
 *
 * The wording is decided by `tasks/` — it is task policy, and it is localized — while whether the
 * anchor still exists, and what to do when it does not, is the adapter's to find out.
 */
data class Attribution(
    val anchorMessageId: Long?,
    val headerText: String
)
