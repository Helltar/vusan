package com.helltar.vusan.telegram

import org.telegram.telegrambots.meta.api.methods.GetMe
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * Who this bot is on Telegram, read once at startup.
 *
 * Two places need it and neither can derive it: [TelegramBotRunner] matches mentions and commands
 * addressed to this account, and the agent has to be told its own handles outright — inbound
 * sanitizing strips the mention before the prompt is built, so the model would otherwise never see
 * the names people call it by.
 *
 * A bot may hold more than one handle: the one it was created with, which ends in `bot`, and a premium
 * one (`@name_agent`, `@name_ai`) its owner adds. [usernames] lists every active one in the order the
 * profile shows them, and a mention of any of them is addressed to this bot.
 */
internal data class BotProfile(
    val userId: Long,
    val usernames: List<String>,
    val displayName: String?,
) {

    /** The handle the profile shows first. */
    val username: String?
        get() = usernames.firstOrNull()
}

// the first requests a start makes, so an outage met here is waited out rather than ending the process.
// getMe names a single handle, and which one follows the order the owner set, so the full list is read
// from getChat on the bot itself.
internal suspend fun TelegramClient.botProfile(): BotProfile {
    val me = waitingOutTelegram("getMe") { api { executeAsync(GetMe()) } }
    val chat = waitingOutTelegram("getChat") { api { executeAsync(GetChat.builder().chatId(me.id).build()) } }

    return BotProfile(
        userId = me.id,
        usernames = (chat.activeUsernames.orEmpty() + listOfNotNull(me.userName)).distinctBy(String::lowercase),
        displayName = me.firstName,
    )
}

/**
 * The names people call the bot by in a group: its profile name first, then the ones the deployment
 * lists — or, when it lists none, its handles without the suffix Telegram makes every handle end in,
 * since `@vusanbot` is spoken of as «vusan».
 */
internal fun BotProfile.addressingNames(configured: List<String>): List<String> {
    val spoken = configured.ifEmpty { usernames.map { it.replace(HANDLE_SUFFIX, "") } }

    return (listOfNotNull(displayName) + spoken)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)
}

// `bot` for the handle a bot is created with, the rest for the premium one telegram's server allows today
private val HANDLE_SUFFIX = Regex("_?(bot|agent|ai|app|game|gay)$", RegexOption.IGNORE_CASE)
