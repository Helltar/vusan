package com.helltar.vusan.delivery

import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.UserRef

/**
 * Where an answer belongs.
 *
 * A destination and its sub-conversation are separate things, and the message an answer hangs under is a
 * third, on [Attribution]: a turn nothing sent, such as a scheduled task, has a place to write but no anchor,
 * and never invents one.
 *
 * [threadId] is opaque to all but the adapter that issued it: a telegram forum topic and a discord thread are
 * different objects, and neither is the conversation; the chat is.
 */
data class Destination(
    val chat: ChatRef,
    val threadId: String? = null,
)

/**
 * The line naming whose scheduled answer this is, and where it hangs. Why the bot writes unprompted is task
 * policy (a standing order reads differently from a follow-up), but naming the person is the platform's own
 * syntax, so the adapter is told who and writes the line. Whether the anchor still exists is also the
 * adapter's to find out.
 */
data class Attribution(
    val anchorMessageId: String?,
    val person: UserRef,
    val displayName: String? = null,
    val username: String? = null,
    val reason: AttributionReason,
)

/** Why an answer nobody just asked for is arriving. */
enum class AttributionReason {

    /** A task its owner set up fired. */
    SCHEDULED,

    /** The bot set itself a follow-up in this conversation. */
    FOLLOW_UP
}
