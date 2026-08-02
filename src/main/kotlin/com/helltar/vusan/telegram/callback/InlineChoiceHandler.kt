package com.helltar.vusan.telegram.callback

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.telegram.delivery.answerCallbackQuery
import com.helltar.vusan.telegram.delivery.editTextMessage
import com.helltar.vusan.telegram.delivery.isMessageNotModified
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.LinkedHashMap
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.generics.TelegramClient

private const val COMPACT_BUTTON_MAX_CHARS = 24
private const val INLINE_CHOICE_CALLBACK_PREFIX = "choice:"

internal data class InlineChoiceSelection(
    val question: String,
    val option: String
)

internal class InlineChoiceHandler(
    private val client: TelegramClient,
    private val currentHistoryRevision: suspend (Long) -> Long
) {

    private companion object {
        const val MAX_RECENT_SELECTIONS = 1_000

        val log = KotlinLogging.logger {}
    }

    private data class MessageKey(val chatId: Long, val messageId: Int)

    private val claimedMessages =
        object : LinkedHashMap<MessageKey, Unit>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MessageKey, Unit>?): Boolean =
                size > MAX_RECENT_SELECTIONS
        }

    fun handles(callbackData: String?): Boolean =
        callbackData?.startsWith(INLINE_CHOICE_CALLBACK_PREFIX) == true

    suspend fun handleCallback(
        callbackQueryId: String,
        callbackData: String,
        userId: Long,
        chatId: Long,
        messageId: Int,
        question: String?,
        keyboard: InlineKeyboardMarkup?,
        messages: Messages
    ): InlineChoiceSelection? {
        val action =
            InlineChoiceAction.parse(callbackData)
                ?: return unavailable(callbackQueryId, messages)

        if (action.ownerId != userId) {
            answerCallbackQuery(
                client,
                callbackQueryId,
                messages.inlineChoiceNotOwnerAlert,
                showAlert = true
            )
            return null
        }

        if (action.historyRevision != currentHistoryRevision(userId))
            return unavailable(callbackQueryId, messages)

        val normalizedQuestion =
            question?.trim()?.takeIf { it.isNotEmpty() }
                ?: return unavailable(callbackQueryId, messages)

        val option =
            keyboard
                ?.keyboard
                .orEmpty()
                .flatten()
                .getOrNull(action.optionIndex)
                ?.takeIf { it.callbackData == callbackData }
                ?.text
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return unavailable(callbackQueryId, messages)

        val key = MessageKey(chatId, messageId)

        if (!claim(key))
            return unavailable(callbackQueryId, messages)

        val selectedText = "$normalizedQuestion\n\n${messages.inlineChoiceSelected(option)}"

        try {
            editTextMessage(
                client = client,
                chatId = chatId,
                messageId = messageId,
                text = selectedText,
                replyMarkup = emptyInlineKeyboard()
            )
        } catch (error: Throwable) {
            error.rethrowIfCancellation()

            if (error.isMessageNotModified()) {
                unavailable(callbackQueryId, messages)
                return null
            }

            release(key)

            runCatching {
                answerCallbackQuery(
                    client,
                    callbackQueryId,
                    messages.inlineChoiceErrorAlert,
                    showAlert = true
                )
            }.onFailure { it.rethrowIfCancellation() }

            throw error
        }

        runCatching { answerCallbackQuery(client, callbackQueryId) }
            .onFailure {
                it.rethrowIfCancellation()
                log.warn(it) { "failed to answer inline choice callback query" }
            }

        return InlineChoiceSelection(normalizedQuestion, option)
    }

    suspend fun answerUnavailable(callbackQueryId: String, messages: Messages) {
        answerCallbackQuery(
            client,
            callbackQueryId,
            messages.inlineChoiceUnavailableAlert,
            showAlert = true
        )
    }

    private suspend fun unavailable(callbackQueryId: String, messages: Messages): InlineChoiceSelection? {
        answerUnavailable(callbackQueryId, messages)
        return null
    }

    private fun claim(key: MessageKey): Boolean =
        synchronized(claimedMessages) {
            if (key in claimedMessages)
                false
            else {
                claimedMessages[key] = Unit
                true
            }
        }

    private fun release(key: MessageKey) {
        synchronized(claimedMessages) {
            claimedMessages.remove(key)
        }
    }
}

internal fun inlineChoiceKeyboard(choice: BotOutput.InlineChoice): InlineKeyboardMarkup {
    val buttons =
        choice.options.mapIndexed { index, option ->
            InlineKeyboardButton.builder()
                .text(option)
                .callbackData(InlineChoiceAction(choice.ownerId, choice.historyRevision, index).serialize())
                .build()
        }

    val buttonsPerRow =
        if (choice.options.all { it.length <= COMPACT_BUTTON_MAX_CHARS })
            2
        else
            1

    return InlineKeyboardMarkup.builder()
        .keyboard(buttons.chunked(buttonsPerRow).map { InlineKeyboardRow(it) })
        .build()
}

internal fun inlineChoiceAgentInput(selection: InlineChoiceSelection): String =
    xmlBlock(
        "inline_choice",
        buildString {
            appendLine(xmlBlock("question", selection.question))
            append(xmlBlock("selected_option", selection.option))
        }
    )

private fun emptyInlineKeyboard(): InlineKeyboardMarkup =
    InlineKeyboardMarkup.builder()
        .keyboard(emptyList<InlineKeyboardRow>())
        .build()

private data class InlineChoiceAction(
    val ownerId: Long,
    val historyRevision: Long,
    val optionIndex: Int
) {
    fun serialize(): String = "$INLINE_CHOICE_CALLBACK_PREFIX$ownerId:$historyRevision:$optionIndex"

    companion object {
        fun parse(raw: String): InlineChoiceAction? {
            if (!raw.startsWith(INLINE_CHOICE_CALLBACK_PREFIX)) return null

            val parts = raw.removePrefix(INLINE_CHOICE_CALLBACK_PREFIX).split(':')
            if (parts.size != 3) return null

            val ownerId = parts[0].toLongOrNull()?.takeIf { it > 0L } ?: return null
            val historyRevision = parts[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
            val optionIndex = parts[2].toIntOrNull()?.takeIf { it in 0..9 } ?: return null
            return InlineChoiceAction(ownerId, historyRevision, optionIndex)
        }
    }
}
