package com.helltar.vusan.request

data class RequestContext(
    val chatId: Long,
    val userId: Long,
    val messageId: Long,
    val replyToMessageId: Long? = null,
    val repliedPhoto: RepliedPhoto? = null,
    val senderUsername: String? = null,
    val senderDisplayName: String? = null,
    val chatIsPrivate: Boolean = true,
)
