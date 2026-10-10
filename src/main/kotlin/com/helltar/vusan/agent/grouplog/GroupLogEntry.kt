package com.helltar.vusan.agent.grouplog

import com.helltar.vusan.request.ChatRef
import java.time.Instant

/**
 * One message seen in a group chat. Media is reduced to [kind] plus a short human [descriptor];
 * the file itself is never stored, and neither are the Telegram file ids that would let it be fetched.
 */
data class GroupLogEntry(
    val chat: ChatRef,
    val messageId: String?,
    val kind: String,
    val sentAt: Instant,
    val senderId: String? = null,
    val senderUsername: String? = null,
    val senderName: String? = null,
    val text: String? = null,
    val descriptor: String? = null,
    val forwardFrom: String? = null,
    val replyToMessageId: String? = null,
) {

    init {
        require(kind.isNotBlank()) { "Chat log entry kind must not be blank" }
        require((text?.length ?: 0) <= MAX_TEXT_CHARS) { "Chat log entry text over $MAX_TEXT_CHARS chars" }
        require((descriptor?.length ?: 0) <= MAX_DESCRIPTOR_CHARS) { "Chat log entry descriptor over $MAX_DESCRIPTOR_CHARS chars" }
        require((forwardFrom?.length ?: 0) <= MAX_FORWARD_FROM_CHARS) { "Chat log entry forward label over $MAX_FORWARD_FROM_CHARS chars" }
    }

    companion object {
        /** [kind] of a message the bot itself sent into the chat. */
        const val BOT_KIND = "bot"

        // what one message may cost the log, the bot's own lines included; every writer caps to these,
        // and the two varchar widths in GroupLogTable mirror the last two
        const val MAX_TEXT_CHARS = 2_000
        const val MAX_DESCRIPTOR_CHARS = 200
        const val MAX_FORWARD_FROM_CHARS = 128
    }
}

/** One person's share of a chat over some stretch: under the name the transcript shows them by. */
data class AuthorActivity(val name: String, val messages: Long, val lastSeenAt: Instant)
