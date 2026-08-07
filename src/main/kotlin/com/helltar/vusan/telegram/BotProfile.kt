package com.helltar.vusan.telegram

import org.telegram.telegrambots.meta.api.methods.GetMe
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * Who this bot is on Telegram, read once at startup.
 *
 * Two places need it and neither can derive it: [TelegramBotRunner] matches mentions and commands
 * addressed to this account, and the agent has to be told its own handle outright — inbound
 * sanitizing strips the mention before the prompt is built, so the model would otherwise never see
 * the name people call it by.
 */
internal data class BotProfile(
    val userId: Long,
    val username: String?,
    val displayName: String?
)

internal suspend fun TelegramClient.botProfile(): BotProfile =
    api { executeAsync(GetMe()) }
        .let { BotProfile(userId = it.id, username = it.userName, displayName = it.firstName) }
