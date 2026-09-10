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
    val threadId: String? = null,
    val senderId: String? = null,
    val senderUsername: String? = null,
    val senderName: String? = null,
    val text: String? = null,
    val descriptor: String? = null,
    val forwardFrom: String? = null,
    val replyToMessageId: String? = null
) {
    init {
        require(kind.isNotBlank()) { "Chat log entry kind must not be blank" }
    }

    companion object {
        /** [kind] of a message the bot itself sent into the chat. */
        const val BOT_KIND = "bot"
    }
}
