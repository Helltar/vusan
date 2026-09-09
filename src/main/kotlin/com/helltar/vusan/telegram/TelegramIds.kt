package com.helltar.vusan.telegram

import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.Platform
import com.helltar.vusan.request.UserRef

/**
 * Telegram issues numeric ids and the Bot API takes them back as numbers, while the shared core treats
 * an external id as opaque text — a messenger whose ids are not numbers at all has to fit there too.
 *
 * Both conversions live here, so nothing outside the adapter has to know Telegram's width, and a
 * reference from somewhere else cannot be silently read as a Telegram one.
 */
internal fun telegramChat(id: Long): ChatRef = ChatRef(Platform.TELEGRAM, id.toString())

internal fun telegramUser(id: Long): UserRef = UserRef(Platform.TELEGRAM, id.toString())

internal val ChatRef.telegramChatId: Long
    get() = numericId("chat")

internal val UserRef.telegramUserId: Long
    get() = numericId("user")

private fun ChatRef.numericId(label: String): Long = numeric(platform, id, label)

private fun UserRef.numericId(label: String): Long = numeric(platform, id, label)

private fun numeric(platform: Platform, id: String, label: String): Long {
    require(platform == Platform.TELEGRAM) { "Not a Telegram $label: [$platform:$id]" }
    return requireNotNull(id.toLongOrNull()) { "Telegram $label id is not numeric: [$id]" }
}
