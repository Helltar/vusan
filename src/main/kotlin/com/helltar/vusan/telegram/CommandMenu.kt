package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllGroupChats
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllPrivateChats
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault
import org.telegram.telegrambots.meta.generics.TelegramClient

private val log = KotlinLogging.logger {}

private fun ephemeralCommand(command: String, description: String): BotCommand =
    BotCommand.builder().command(command).description(description).isEphemeral(true).build()

internal const val TASKS_COMMAND = "tasks"
internal const val CLEAR_COMMAND = "clear"
internal const val STOP_COMMAND = "stop"

/**
 * Publishes the command menu telegram shows beside the input field, one list per [Language].
 *
 * It is the same list BotFather's `/setcommands` writes, so it is republished on every start and a BotFather
 * edit lasts only until the next one. Adding a command to `TelegramCommands` is all it takes to offer it.
 *
 * `/start` is left out on purpose: telegram opens a fresh chat with it anyway.
 */
internal suspend fun TelegramClient.publishCommandMenu() {
    Language.entries.forEach { language ->
        val messages = Messages.of(language)

        // the task menu and a clear are personal, so in a group they are ephemeral, seen by that person and
        // the bot alone; clients hide ephemeral commands in private chats, so the private list has them
        // plain. a stop stays in the open on purpose: the chat sees why the bot fell silent.
        fun commands(personal: (String, String) -> BotCommand): List<BotCommand> =
            listOf(
                personal(TASKS_COMMAND, messages.tasksCommandDescription),
                personal(CLEAR_COMMAND, messages.clearCommandDescription),
                BotCommand(STOP_COMMAND, messages.stopCommandDescription),
            )

        // the list without a language serves everyone whose language has no list of its own
        val languageCode = language.code.takeUnless { language == Language.DEFAULT }
        val plain = commands(::BotCommand)
        val ephemeral = commands(::ephemeralCommand)

        // a private chat reads its own scope before the default, a group its own; the default covers
        // everything else
        listOf(
            BotCommandScopeAllPrivateChats() to plain,
            BotCommandScopeAllGroupChats() to ephemeral,
            BotCommandScopeDefault() to plain,
        ).forEach { (scope, list) ->
            runCatching {
                api { executeAsync(SetMyCommands.builder().commands(list).scope(scope).languageCode(languageCode).build()) }
            }.onFailure { error ->
                error.rethrowIfCancellation()
                log.warn { "failed to publish the $language command menu for ${scope.type}: ${error.message}" }
            }
        }
    }
}
