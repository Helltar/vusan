package com.helltar.vusan.delivery

import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.UserRef

/**
 * Where an answer belongs.
 *
 * A destination and the sub-conversation inside it are two different things, and the message an answer
 * hangs under is a third that lives on [Attribution] instead: a turn nothing sent — a scheduled task
 * firing — has a place to write to but no anchor, and never invents one.
 *
 * [threadId] is opaque to everything but the adapter that issued it. A Telegram forum topic and a
 * Discord thread are not the same kind of object, and neither is the conversation: the chat is.
 */
data class Destination(
    val chat: ChatRef,
    val threadId: String? = null
)

/**
 * The line naming who a scheduled answer belongs to, and where it hangs.
 *
 * Why a chat is being written to unprompted is task policy — a standing order somebody set up reads
 * differently from the bot returning to a conversation — but how the person is named is not: a
 * mention is the platform's own syntax, so the adapter is told who it is talking about and writes
 * the line itself. Whether the anchor still exists is likewise the adapter's to find out.
 */
data class Attribution(
    val anchorMessageId: String?,
    val person: UserRef,
    val displayName: String? = null,
    val username: String? = null,
    val reason: AttributionReason
)

/** Why an answer nobody just asked for is arriving. */
enum class AttributionReason {

    /** A task its owner set up fired. */
    SCHEDULED,

    /** The bot set itself a follow-up in this conversation. */
    FOLLOW_UP
}
