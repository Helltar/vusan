package com.helltar.vusan.telegram.delivery

import com.helltar.vusan.telegram.LocalFileTooBigException
import java.io.IOException
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TOO_MANY_REQUESTS = 429
private const val SERVER_ERRORS = 500

// every parse-mode failure starts with "Bad Request: can't parse entities: ..." whatever the HTML problem.
internal fun Throwable.isEntityParseError(): Boolean =
    telegramDescription?.contains("can't parse entities", ignoreCase = true) == true

// a private delivery refused by the recipient is a 403 "Forbidden: ..." (blocked, can't initiate,
// deactivated) or "Bad Request: chat not found" when the user never interacted. only the leading word counts:
// content rejections such as `VOICE_MESSAGES_FORBIDDEN` refuse one output kind in a chat that takes the rest.
internal fun Throwable.isForbidden(): Boolean {
    val description = telegramDescription?.lowercase() ?: return false

    return description.startsWith("forbidden:") || "chat not found" in description
}

// nothing can be delivered here any more: kicked or left, blocked, the chat gone, or the right to write taken
// away. unlike one refused output kind, which the send fallbacks handle, this makes every further send into
// the chat wasted.
internal fun Throwable.isChatUnreachable(): Boolean {
    if (isForbidden()) return true

    val description = telegramDescription?.lowercase() ?: return false

    return "chat_write_forbidden" in description ||
        "not enough rights to send text messages" in description ||
        "group chat was deactivated" in description
}

// telegram has used both wordings for a missing reply target.
internal fun Throwable.isReplyMessageNotFound(): Boolean {
    val description = telegramDescription?.lowercase() ?: return false

    return "reply message not found" in description || "message to be replied not found" in description
}

// an ephemeral message may be answered only within 15 seconds; after that the anchor is refused with this
// code, as is an anchor on message id 0, which every ephemeral message has.
internal fun Throwable.isReplyTargetInvalid(): Boolean =
    telegramDescription?.contains("REPLY_TO_INVALID", ignoreCase = true) == true

internal fun Throwable.isMessageNotModified(): Boolean =
    telegramDescription?.contains("message is not modified", ignoreCase = true) == true

// the message to edit or delete is gone (removed, or too old to touch). this never recovers, so the caller
// stops writing to it instead of retrying.
internal fun Throwable.isMessageGone(): Boolean {
    val description = telegramDescription?.lowercase() ?: return false

    return "message to edit not found" in description ||
        "message to delete not found" in description ||
        "message can't be edited" in description ||
        "message can't be deleted" in description
}

// a `file_id` telegram no longer accepts. wordings vary and a miss is cheap: a match only schedules an early
// re-read of the set, so a miss costs a day and a false positive one extra `getStickerSet`.
internal fun Throwable.isWrongFileIdentifier(): Boolean {
    val description = telegramDescription?.lowercase() ?: return false

    return "wrong file identifier" in description ||
        "wrong remote file identifier" in description ||
        "invalid file_id" in description
}

// a file past what the bot may fetch: telegram's getFile refuses one over its 20 MB, and with a local server
// the bot refuses one over its own limit before reading it.
internal fun Throwable.isFileTooBig(): Boolean =
    this is LocalFileTooBigException || telegramDescription?.contains("file is too big", ignoreCase = true) == true

// a set its owner deleted or renamed, in the raw core error or the readable wording. only these mean gone;
// anything else is transient and must not drop what was learned.
internal fun Throwable.isStickerSetGone(): Boolean {
    val description = telegramDescription?.lowercase() ?: return false

    return "stickerset_invalid" in description || "sticker set not found" in description
}

// sent with no answer back (a timeout, a dropped connection, no api error in the chain). telegram may have
// delivered it, so resending in any shape risks showing it twice.
internal fun Throwable.isUnanswered(): Boolean =
    telegramRequestError == null && causes.any { it is IOException }

// telegram down or overloaded rather than refusing: a 5xx from its gateway, or no answer. worth retrying,
// unlike a 4xx.
internal fun Throwable.isTelegramUnavailable(): Boolean =
    isUnanswered() || telegramRequestError?.errorCode?.let { it >= SERVER_ERRORS } == true

/**
 * How long telegram's flood control says to wait, or `null` when the failure was not a 429. The number is
 * exact where local pacing is a guess, but may be far longer than a turn is worth, so the caller decides what
 * it will sit through.
 */
internal fun Throwable.retryAfterOrNull(): Duration? =
    telegramRequestError
        ?.takeIf { it.errorCode == TOO_MANY_REQUESTS }
        ?.parameters
        ?.retryAfter
        ?.seconds

private val Throwable.telegramDescription: String?
    get() = telegramRequestError?.apiResponse

// the client wraps failures in generic TelegramApiException chains, so find the one carrying telegram's
// description and parameters.
private val Throwable.telegramRequestError: TelegramApiRequestException?
    get() = causes.filterIsInstance<TelegramApiRequestException>().firstOrNull()

private val Throwable.causes: Sequence<Throwable>
    get() = generateSequence(this) { error -> error.cause.takeIf { it !== error } }
