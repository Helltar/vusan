package com.helltar.vusan.telegram

import dev.inmo.tgbotapi.bot.exceptions.BotException
import dev.inmo.tgbotapi.bot.exceptions.ReplyMessageNotFoundException

// telegram prefixes every parse-mode failure with "Bad Request: can't parse entities: ...",
// regardless of the specific HTML wording (unsupported tag, unclosed tag, unescaped `<`/`&`, ...).
internal fun Throwable.isEntityParseError(): Boolean =
    telegramDescription?.contains("can't parse entities", ignoreCase = true) == true

// a private delivery rejected by the recipient comes back as a 403 "Forbidden: ..." (bot blocked,
// can't initiate conversation, user deactivated) or as "Bad Request: chat not found" when the user
// never interacted with the bot.
internal fun Throwable.isForbidden(): Boolean {
    val description = telegramDescription?.lowercase() ?: return false
    return "forbidden" in description || "chat not found" in description
}

// keep the description fallback because ktgbotapi's concrete exception type depends on its
// classification table and whether Telegram included an error code in the response.
internal fun Throwable.isReplyMessageNotFound(): Boolean =
    this is ReplyMessageNotFoundException ||
        telegramDescription?.contains("message to be replied not found") == true

private val Throwable.telegramDescription: String?
    get() = (this as? BotException)?.response?.description
