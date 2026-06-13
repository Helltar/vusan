package com.helltar.vusan.telegram

import dev.inmo.tgbotapi.bot.exceptions.ReplyMessageNotFoundException
import dev.inmo.tgbotapi.bot.exceptions.RequestException

// telegram prefixes every parse-mode failure with "Bad Request: can't parse entities: ...",
// regardless of the specific Markdown/MarkdownV2 wording (unclosed entity, reserved character, ...).
internal fun RequestException.isMarkdownError(): Boolean =
    response.description?.contains("can't parse entities", ignoreCase = true) == true

// a private delivery rejected by the recipient comes back as a 403 "Forbidden: ..." (bot blocked,
// can't initiate conversation, user deactivated) or as "Bad Request: chat not found" when the user
// never interacted with the bot.
internal fun RequestException.isForbidden(): Boolean {
    val description = response.description?.lowercase() ?: return false
    return "forbidden" in description || "chat not found" in description
}

// ktgbotapi classifies only the older telegram wordings ("reply message not found",
// "replied message not found"); newer Bot API versions return
// "message to be replied not found", which falls through to CommonRequestException.
internal fun Throwable.isReplyMessageNotFound(): Boolean =
    this is ReplyMessageNotFoundException ||
        this is RequestException && response.description?.contains("message to be replied not found") == true
