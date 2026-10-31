package com.helltar.vusan.telegram

import org.telegram.telegrambots.meta.api.methods.GetMe
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * Who this bot is on telegram, read once at startup.
 *
 * [TelegramBotRunner] matches mentions and commands against it, and the agent is told its handles outright,
 * since inbound sanitizing strips the mention and the model would never see the names people use.
 *
 * A bot may hold several handles: the original one ending in `bot` and a premium one (`@name_agent`,
 * `@name_ai`). [usernames] lists every active one in profile order, and a mention of any addresses this bot.
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

// the first requests of a start, so an outage here is waited out. getMe names one handle, chosen by the
// owner's order, so the full list comes from getChat on the bot itself.
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
 * The names people call the bot by in a group: its profile name first, then the configured ones or, without
 * any, its handles minus telegram's suffix, since `@vusanbot` is spoken of as «vusan».
 */
internal fun BotProfile.addressingNames(configured: List<String>): List<String> {
    val spoken = configured.ifEmpty { usernames.map { it.replace(HANDLE_SUFFIX, "") } }

    return (listOfNotNull(displayName) + spoken)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)
}

// `bot` for the original handle, the rest for the premium ones telegram allows today
private val HANDLE_SUFFIX = Regex("_?(bot|agent|ai|app|game|gay)$", RegexOption.IGNORE_CASE)
