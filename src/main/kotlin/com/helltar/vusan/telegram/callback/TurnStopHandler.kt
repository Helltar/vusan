package com.helltar.vusan.telegram.callback

import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.telegram.delivery.answerCallbackQuery
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.generics.TelegramClient

private const val CALLBACK_PREFIX = "stop:"

/** The data behind the stop button a live status carries, read back by [turnStopOwnerId]. */
internal fun turnStopCallbackData(ownerId: Long): String = "$CALLBACK_PREFIX$ownerId"

/** Whose turn a stop button belongs to, or `null` for anything this build did not write. */
internal fun turnStopOwnerId(callbackData: String): Long? =
    callbackData.takeIf { it.startsWith(CALLBACK_PREFIX) }?.removePrefix(CALLBACK_PREFIX)?.toLongOrNull()

/**
 * The stop button on a turn's live status. It does exactly what `/stop` does — one `AgentRunner.stop`
 * on the same conversation — and exists because in a group the typed command has to be addressed
 * (`/stop@bot`) to be seen at all, while a button is one tap.
 *
 * The button sits on a message everyone in the chat can press, so the turn's owner travels in the
 * callback data: without that check a bystander could end somebody else's turn.
 */
internal class TurnStopHandler(
    private val client: TelegramClient,
    private val agent: AgentRunner
) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    fun handles(callbackData: String?): Boolean =
        callbackData?.startsWith(CALLBACK_PREFIX) == true

    suspend fun handleCallback(
        callbackQueryId: String,
        callbackData: String,
        userId: Long,
        chatId: Long,
        messages: Messages
    ) {
        val ownerId = turnStopOwnerId(callbackData)

        if (ownerId != userId) {
            // a button from a build that wrote its data differently reads as somebody else's turn, which
            // is the safe way to be wrong: nothing is stopped and the presser is told why.
            answerCallbackQuery(client, callbackQueryId, messages.turnStopNotOwnerAlert, showAlert = true)
            return
        }

        if (agent.stop(userId, chatId)) {
            log.info { "stopped the running turn from the status button: chat=$chatId user=$userId" }
            // the stopped turn reports itself in the chat, so the toast would only repeat it.
            answerCallbackQuery(client, callbackQueryId)
            return
        }

        // the turn ended between the render and the tap, or this is a button left over from an older one.
        answerCallbackQuery(client, callbackQueryId, messages.nothingToStopReply)
    }
}
