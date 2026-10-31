package com.helltar.vusan.telegram.callback

import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.telegram.telegramChat
import com.helltar.vusan.telegram.telegramUser
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.telegram.delivery.answerCallbackQuery
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.generics.TelegramClient

private const val CALLBACK_PREFIX = "stop:"

/** The data behind a live status's stop button, read back by [turnStopOwnerId]. */
internal fun turnStopCallbackData(ownerId: Long): String = "$CALLBACK_PREFIX$ownerId"

/** Whose turn a stop button belongs to, or `null` for anything this build did not write. */
internal fun turnStopOwnerId(callbackData: String): Long? =
    callbackData.takeIf { it.startsWith(CALLBACK_PREFIX) }?.removePrefix(CALLBACK_PREFIX)?.toLongOrNull()

/**
 * The stop button on a turn's live status: exactly `/stop`, one `AgentRunner.stop` on the same conversation.
 * It exists because in a group the typed command must be addressed (`/stop@bot`), while a button is one tap.
 *
 * Everyone in the chat can press it, so the turn's owner travels in the callback data: otherwise a bystander
 * could end someone else's turn.
 */
internal class TurnStopHandler(
    private val client: TelegramClient,
    private val agent: AgentRunner,
) {

    fun handles(callbackData: String?): Boolean =
        callbackData?.startsWith(CALLBACK_PREFIX) == true

    suspend fun handleCallback(
        callbackQueryId: String,
        callbackData: String,
        userId: Long,
        chatId: Long,
        messages: Messages,
    ) {
        val ownerId = turnStopOwnerId(callbackData)

        if (ownerId != userId) {
            // data from a build that wrote it differently reads as someone else's turn, the safe way to be
            // wrong: nothing stops and the presser is told why.
            answerCallbackQuery(client, callbackQueryId, messages.turnStopNotOwnerAlert, showAlert = true)
            return
        }

        if (agent.stop(ConversationScope(telegramUser(userId), telegramChat(chatId)))) {
            log.info { "stopped the running turn from the status button: chat=$chatId user=$userId" }
            // the stopped turn reports itself in the chat; a toast would repeat it.
            answerCallbackQuery(client, callbackQueryId)
            return
        }

        // the turn ended between render and tap, or the button is left over from an older one.
        answerCallbackQuery(client, callbackQueryId, messages.nothingToStopReply)
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
