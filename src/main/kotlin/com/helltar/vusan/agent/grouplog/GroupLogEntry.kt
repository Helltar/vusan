package com.helltar.vusan.agent.grouplog

import java.time.Instant

/**
 * One message seen in a group chat. Media is reduced to [kind] plus a short human [descriptor];
 * the file itself is never stored, and neither are the Telegram file ids that would let it be fetched.
 */
data class GroupLogEntry(
    val chatId: Long,
    val messageId: Long?,
    val kind: String,
    val sentAt: Instant,
    val threadId: Long? = null,
    val senderId: Long? = null,
    val senderUsername: String? = null,
    val senderName: String? = null,
    val text: String? = null,
    val descriptor: String? = null,
    val forwardFrom: String? = null,
    val replyToMessageId: Long? = null
) {
    init {
        require(kind.isNotBlank()) { "Chat log entry kind must not be blank" }
    }

    companion object {
        /** [kind] of a message the bot itself sent into the chat. */
        const val BOT_KIND = "bot"
    }
}
