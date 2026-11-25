package com.helltar.vusan.telegram.callback

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.telegram.delivery.ChatTarget
import com.helltar.vusan.telegram.delivery.answerCallbackQuery
import com.helltar.vusan.telegram.delivery.sendCallbackReply
import com.helltar.vusan.telegram.delivery.sendTextMessage
import com.helltar.vusan.telegram.inbound.isPerson
import com.helltar.vusan.telegram.overviewReply
import com.helltar.vusan.tools.sandbox.SandboxClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.api.methods.ParseMode
import org.telegram.telegrambots.meta.api.objects.ReplyParameters
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.generics.TelegramClient

/** What a person may ask to see of their own in a group: what `/tasks` and `/sandbox` answer. */
internal enum class OwnView(val callbackData: String) {
    TASKS("own:tasks"),
    SANDBOX("own:sandbox"),
}

/**
 * The open stand-in for an answer meant for one person. A plain `/tasks` or `/sandbox` in a group can be
 * answered for its sender's eyes only by an administrator, so where telegram refuses that, the chat gets a
 * button instead, and whoever presses it sees their own tasks or sandbox as the ephemeral reply to the press,
 * which needs no admin right, only an answer within telegram's 15 seconds.
 */
internal class OwnViewHandler(
    private val client: TelegramClient,
    private val taskMenu: TaskMenuHandler,
    // null where no sandbox server is configured: no `/sandbox`, so no button for it either
    private val sandbox: SandboxClient? = null,
) {

    fun handles(callbackData: String?): Boolean = OwnView.entries.any { it.callbackData == callbackData }

    /** Posts in the open the button that shows [view] to whoever presses it. */
    suspend fun sendPrompt(view: OwnView, target: ChatTarget, replyParameters: ReplyParameters?, messages: Messages) {
        val label =
            when (view) {
                OwnView.TASKS -> messages.ownTasksButton
                OwnView.SANDBOX -> messages.ownSandboxButton
            }

        val keyboard =
            InlineKeyboardMarkup.builder()
                .keyboardRow(InlineKeyboardRow(InlineKeyboardButton.builder().text(label).callbackData(view.callbackData).build()))
                .build()

        sendTextMessage(client, target, messages.ownViewPrompt, parseMode = null, replyParameters, keyboard)
    }

    suspend fun handleCallback(callbackQueryId: String, callbackData: String, user: User, chatId: Long, messages: Messages) {
        val view = OwnView.entries.first { it.callbackData == callbackData }

        runCatching {
            when (view) {
                OwnView.TASKS -> taskMenu.sendMenuAsCallbackReply(callbackQueryId, user.id, chatId, messages)
                OwnView.SANDBOX -> sendCallbackReply(client, callbackQueryId, sandboxReply(user, messages), ParseMode.HTML)
            }
        }.onSuccess {
            answerCallbackQuery(client, callbackQueryId)
        }.onFailure { error ->
            error.rethrowIfCancellation()
            log.warn { "no ephemeral reply to an own-view press: view=[$view] chat=[$chatId] user=[${user.id}]: ${error.message}" }
            answerCallbackQuery(client, callbackQueryId, messages.ownViewFailedAlert, showAlert = true)
        }
    }

    // a sandbox is filed under one person, which the account anonymous admins and channels share is not
    private suspend fun sandboxReply(user: User, messages: Messages): String {
        val client = sandbox ?: return messages.sandboxUnavailableReply

        return if (user.isPerson) client.overviewReply(user.id, messages) else messages.sandboxNotPersonalReply
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
