package com.helltar.vusan.outbox

data class RequestContext(
    val chatId: Long,
    val userId: Long,
    val messageId: Long,
    val replyToMessageId: Long? = null,
    val repliedPhoto: RepliedPhoto? = null,
)
