package com.helltar.vusan.request

import com.helltar.vusan.i18n.Language

data class RequestContext(
    val chatId: Long,
    val userId: Long,
    val messageId: Long,
    val replyToMessageId: Long? = null,
    val repliedPhoto: RepliedPhoto? = null,
    val senderUsername: String? = null,
    val senderDisplayName: String? = null,
    val chatIsPrivate: Boolean = true,
    val language: Language = Language.DEFAULT
)
