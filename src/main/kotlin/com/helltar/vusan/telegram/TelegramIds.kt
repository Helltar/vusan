package com.helltar.vusan.telegram

import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.Platform
import com.helltar.vusan.request.UserRef

/**
 * Telegram ids are numbers and the Bot API takes numbers back, while the shared core treats every external
 * reference (person, chat, topic, message) as opaque text, so a messenger with non-numeric ids fits too.
 *
 * Reading back lives here, so nothing outside the adapter knows telegram's width and no foreign reference is
 * silently read as a telegram one. Writing is `toString()` at the adapter's edges.
 */
internal fun telegramChat(id: Long): ChatRef = ChatRef(Platform.TELEGRAM, id.toString())

internal fun telegramUser(id: Long): UserRef = UserRef(Platform.TELEGRAM, id.toString())

internal val ChatRef.telegramChatId: Long
    get() = numericId("chat")

internal val UserRef.telegramUserId: Long
    get() = numericId("user")

/** A message reference back in the width the Bot API uses. */
internal val String.telegramMessageId: Long
    get() = requireNotNull(toLongOrNull()) { "Telegram message id is not numeric: [$this]" }

/** A forum topic id, an `Int` to the Bot API and opaque to the shared model. */
internal fun telegramThreadId(threadId: String?): Int? =
    threadId?.let { requireNotNull(it.toIntOrNull()) { "Telegram topic id is not numeric: [$it]" } }

private fun ChatRef.numericId(label: String): Long = numeric(platform, id, label)

private fun UserRef.numericId(label: String): Long = numeric(platform, id, label)

private fun numeric(platform: Platform, id: String, label: String): Long {
    require(platform == Platform.TELEGRAM) { "Not a Telegram $label: [$platform:$id]" }

    return requireNotNull(id.toLongOrNull()) { "Telegram $label id is not numeric: [$id]" }
}
