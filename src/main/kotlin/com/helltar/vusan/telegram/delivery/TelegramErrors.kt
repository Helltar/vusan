package com.helltar.vusan.telegram.delivery

import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException

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

// telegram has used both wordings for a missing reply target, so match either.
internal fun Throwable.isReplyMessageNotFound(): Boolean {
    val description = telegramDescription?.lowercase() ?: return false
    return "reply message not found" in description || "message to be replied not found" in description
}

internal fun Throwable.isMessageNotModified(): Boolean =
    telegramDescription?.contains("message is not modified", ignoreCase = true) == true

// a sticker set the owner deleted or renamed. telegram answers `getStickerSet` with either the raw
// core error or the readable wording, and only these mean the set is really gone — any other
// failure is transient and must not be taken as permission to drop what was learned from it.
internal fun Throwable.isStickerSetGone(): Boolean {
    val description = telegramDescription?.lowercase() ?: return false
    return "stickerset_invalid" in description || "sticker set not found" in description
}

// the client wraps request failures in generic TelegramApiException chains, so search the causes
// for the api-level exception that carries telegram's error description.
private val Throwable.telegramDescription: String?
    get() = generateSequence(this) { error -> error.cause.takeIf { it !== error } }
        .filterIsInstance<TelegramApiRequestException>()
        .firstOrNull()
        ?.apiResponse
