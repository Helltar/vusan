package com.helltar.vusan.telegram

/**
 * Group-chat attribution for a scheduled reminder.
 *
 * - [creatorMessageId] is the message where the user created the reminder. When present,
 *   the bot's reply uses it as the reply-to anchor so Telegram natively pings the user
 *   and shows the original request inline.
 * - [headerText] is a pre-built one-line fallback like `⏰ Scheduled by @user`, sent only
 *   when the reply anchor is gone (original message deleted).
 *
 * In private chats no attribution is needed and a `null` is passed instead.
 */
data class ScheduledAttribution(
    val creatorMessageId: Long?,
    val headerText: String,
)
