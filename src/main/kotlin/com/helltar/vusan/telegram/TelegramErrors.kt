package com.helltar.vusan.telegram

import dev.inmo.tgbotapi.bot.exceptions.ReplyMessageNotFoundException
import dev.inmo.tgbotapi.bot.exceptions.RequestException

private val markdownErrorPatterns =
    listOf(
        "can't parse entities",
        "can't find end of",
        "wrong markdown",
        "entity beginning",
        "entity end"
    )

private val forbiddenPatterns =
    listOf(
        "forbidden",
        "bot can't initiate conversation",
        "bot was blocked",
        "user is deactivated",
        "chat not found"
    )

internal fun RequestException.isMarkdownError(): Boolean {
    val description = response.description?.lowercase() ?: return false
    return markdownErrorPatterns.any { it in description } || ("character" in description && "is reserved" in description)
}

internal fun RequestException.isForbidden(): Boolean {
    val description = response.description?.lowercase() ?: return false
    return forbiddenPatterns.any { it in description }
}

// ktgbotapi classifies only the older telegram wordings ("reply message not found",
// "replied message not found"); newer Bot API versions return
// "message to be replied not found", which falls through to CommonRequestException.
internal fun Throwable.isReplyMessageNotFound(): Boolean =
    this is ReplyMessageNotFoundException ||
        this is RequestException && response.description?.contains("message to be replied not found") == true
