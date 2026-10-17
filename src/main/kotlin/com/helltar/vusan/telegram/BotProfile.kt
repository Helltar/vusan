package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.telegram.delivery.isTelegramUnavailable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
    val displayName: String?,
)

private val log = KotlinLogging.logger("BotProfile")

private val FIRST_GETME_WAIT = 1.seconds
private val MAX_GETME_WAIT = 1.minutes

/**
 * Reads [BotProfile] for the account the client is signed in as, waiting for as long as Telegram cannot
 * answer.
 *
 * It is the first request a start makes, so a passing outage would otherwise end the process, and each
 * restart repeats every startup check before asking again. A refusal — a revoked token above all — is
 * not waited out: no wait can fix it.
 */
internal suspend fun TelegramClient.botProfile(): BotProfile {
    var wait = FIRST_GETME_WAIT

    while (true) {
        val me =
            runCatching { api { executeAsync(GetMe()) } }
                .onFailure { error ->
                    error.rethrowIfCancellation()
                    if (!error.isTelegramUnavailable()) throw error
                    log.warn { "Telegram cannot answer getMe yet, asking again in ${wait.inWholeSeconds}s: ${error.message}" }
                }
                .getOrNull()

        if (me != null) return BotProfile(userId = me.id, username = me.userName, displayName = me.firstName)

        delay(wait)
        wait = (wait * 2).coerceAtMost(MAX_GETME_WAIT)
    }
}

/**
 * The names people call the bot by in a group: its profile name first, then the ones the deployment
 * lists — or, when it lists none, its handle without the `bot` Telegram makes every handle end in,
 * since `@vusanbot` is spoken of as «vusan».
 */
internal fun BotProfile.addressingNames(configured: List<String>): List<String> {
    val spoken = configured.ifEmpty { listOfNotNull(username?.replace(BOT_SUFFIX, "")) }

    return (listOfNotNull(displayName) + spoken)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)
}

private val BOT_SUFFIX = Regex("_?bot$", RegexOption.IGNORE_CASE)
