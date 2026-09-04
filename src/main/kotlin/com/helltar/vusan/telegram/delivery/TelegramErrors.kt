package com.helltar.vusan.telegram.delivery

import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException

// telegram prefixes every parse-mode failure with "Bad Request: can't parse entities: ...",
// regardless of the specific HTML wording (unsupported tag, unclosed tag, unescaped `<`/`&`, ...).
internal fun Throwable.isEntityParseError(): Boolean =
    telegramDescription?.contains("can't parse entities", ignoreCase = true) == true

// a private delivery rejected by the recipient comes back as a 403 "Forbidden: ..." (bot blocked,
// can't initiate conversation, user deactivated) or as "Bad Request: chat not found" when the user
// never interacted with the bot. only the leading word counts: telegram also names content rejections
// after it (`VOICE_MESSAGES_FORBIDDEN`), and those are one output kind being refused by a chat that
// still takes everything else.
internal fun Throwable.isForbidden(): Boolean {
    val description = telegramDescription?.lowercase() ?: return false
    return description.startsWith("forbidden:") || "chat not found" in description
}

// nothing at all can be delivered into this chat any more: the bot was kicked or left, the user
// blocked it, the chat is gone, or an admin took its right to write away. distinct from one output
// kind being refused (photos off, text still allowed), which the send fallbacks already handle —
// this one means every further send into the chat, now and on the next fire, is wasted.
internal fun Throwable.isChatUnreachable(): Boolean {
    if (isForbidden()) return true

    val description = telegramDescription?.lowercase() ?: return false

    return "chat_write_forbidden" in description ||
        "not enough rights to send text messages" in description ||
        "group chat was deactivated" in description
}

// telegram has used both wordings for a missing reply target, so match either.
internal fun Throwable.isReplyMessageNotFound(): Boolean {
    val description = telegramDescription?.lowercase() ?: return false
    return "reply message not found" in description || "message to be replied not found" in description
}

internal fun Throwable.isMessageNotModified(): Boolean =
    telegramDescription?.contains("message is not modified", ignoreCase = true) == true

// a `file_id` telegram no longer accepts. the wordings vary and this does not have to catch every
// one: a match only schedules an early re-read of the set, so a miss costs a day's delay and a false
// positive costs one extra `getStickerSet` call.
internal fun Throwable.isWrongFileIdentifier(): Boolean {
    val description = telegramDescription?.lowercase() ?: return false
    return "wrong file identifier" in description ||
        "wrong remote file identifier" in description ||
        "invalid file_id" in description
}

// getFile refuses a file above the 20 MB bot download limit before serving any of it.
internal fun Throwable.isFileTooBig(): Boolean =
    telegramDescription?.contains("file is too big", ignoreCase = true) == true

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
