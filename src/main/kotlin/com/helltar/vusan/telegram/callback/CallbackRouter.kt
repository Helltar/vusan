package com.helltar.vusan.telegram.callback

import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.telegram.AgentTurns
import com.helltar.vusan.telegram.delivery.answerCallbackQuery
import com.helltar.vusan.telegram.denialReason
import com.helltar.vusan.telegram.isIdAllowed
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * Where a pressed button goes. Both flows validate the same three things — the query carries a message,
 * the presser is allowed here, and the button is one this build still knows — before their own handler
 * gets it; the difference is that a task-menu action is answered on the spot, while an inline choice
 * becomes the user's next agent turn.
 */
internal class CallbackRouter(
    private val client: TelegramClient,
    private val taskMenu: TaskMenuHandler,
    private val inlineChoices: InlineChoiceHandler,
    private val turns: AgentTurns,
    private val allowedIds: Set<Long>,
    private val bannedIds: Set<Long>
) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    suspend fun route(callback: CallbackQuery) {
        when {
            taskMenu.handles(callback.data) -> routeTaskMenu(callback)
            inlineChoices.handles(callback.data) -> routeInlineChoice(callback)
            // callback data from a scheme this build no longer knows. telegram keeps the client's button
            // spinning until the query is answered, so answer it anyway.
            else -> answerUnrecognized(callback)
        }
    }

    private suspend fun routeTaskMenu(callback: CallbackQuery) {
        val messages = Messages.forCode(callback.from?.languageCode)
        val message =
            callback.message ?: run {
                taskMenu.answerUnavailable(callback.id, messages)
                return
            }

        val chatId = message.chatId
        val userId = callback.from.id

        if (!isAllowed(chatId, userId)) {
            log.warn { "denied callback (${denialReason(chatId, userId, bannedIds)}): chat=$chatId user=$userId" }
            taskMenu.answerUnavailable(callback.id, messages)
            return
        }

        taskMenu.handleCallback(
            callbackQueryId = callback.id,
            callbackData = callback.data,
            userId = userId,
            chatId = chatId,
            messageId = message.messageId,
            chatIsPrivate = message.chat.isUserChat,
            messages = messages
        )
    }

    private suspend fun routeInlineChoice(callback: CallbackQuery) {
        val user = callback.from
        val messages = Messages.forCode(user?.languageCode)
        val message =
            callback.message as? Message ?: run {
                inlineChoices.answerUnavailable(callback.id, messages)
                return
            }

        val chatId = message.chatId
        val userId = user.id

        if (!isAllowed(chatId, userId)) {
            log.warn {
                "denied inline choice callback (${denialReason(chatId, userId, bannedIds)}): " +
                        "chat=$chatId user=$userId"
            }

            inlineChoices.answerUnavailable(callback.id, messages)
            return
        }

        val selection =
            inlineChoices.handleCallback(
                callbackQueryId = callback.id,
                callbackData = callback.data,
                userId = userId,
                chatId = chatId,
                messageId = message.messageId,
                question = message.text,
                keyboard = message.replyMarkup,
                messages = messages
            ) ?: return

        turns.dispatchSelection(message, user, selection, messages)
    }

    private suspend fun answerUnrecognized(callback: CallbackQuery) {
        log.warn { "unrecognized callback data=[${callback.data}] user=${callback.from?.id}" }
        answerCallbackQuery(client, callback.id)
    }

    private fun isAllowed(chatId: Long, userId: Long?): Boolean = isIdAllowed(chatId, userId, allowedIds, bannedIds)
}
