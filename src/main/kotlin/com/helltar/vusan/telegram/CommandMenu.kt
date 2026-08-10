package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.generics.TelegramClient

private val log = KotlinLogging.logger {}

internal const val TASKS_COMMAND = "tasks"
internal const val CLEAR_COMMAND = "clear"

/**
 * Publish the command menu Telegram offers next to the input field, one list per [Language].
 *
 * This is the very list BotFather's `/setcommands` writes — there is no second store behind it — so the
 * menu is republished on every start and an edit made in BotFather lasts only until the next one. It
 * lives here so that adding a command to `TelegramBotRunner.dispatchText` is all it takes to offer it.
 *
 * `/start` is deliberately absent: Telegram opens a fresh chat with it anyway, and it does not belong in
 * a menu of things to reach for later.
 */
internal suspend fun TelegramClient.publishCommandMenu() {
    Language.entries.forEach { language ->
        val messages = Messages.of(language)

        val commands =
            listOf(
                BotCommand(TASKS_COMMAND, messages.tasksCommandDescription),
                BotCommand(CLEAR_COMMAND, messages.clearCommandDescription)
            )

        // the list without a language answers everyone whose own language has no dedicated one
        val languageCode = language.codes.first().takeUnless { language == Language.DEFAULT }

        runCatching {
            api { executeAsync(SetMyCommands.builder().commands(commands).languageCode(languageCode).build()) }
        }.onFailure { error ->
            error.rethrowIfCancellation()
            log.warn { "failed to publish the $language command menu: ${error.message}" }
        }
    }
}
