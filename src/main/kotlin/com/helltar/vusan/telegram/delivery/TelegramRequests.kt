package com.helltar.vusan.telegram.delivery

import com.helltar.vusan.telegram.api

import java.io.ByteArrayInputStream
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendRichMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.ReplyParameters
import org.telegram.telegrambots.meta.api.objects.media.InputMedia
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.richtext.InputRichMessage
import org.telegram.telegrambots.meta.generics.TelegramClient

// the raw Bot API calls shared by the send policy in TelegramOutputSender and the rejection
// handling in TelegramSendFallbacks. nothing here decides what to try or what to do on failure.

internal suspend fun sendTextMessage(
    client: TelegramClient,
    chatId: Long,
    text: String,
    parseMode: String?,
    replyParameters: ReplyParameters?,
    replyMarkup: InlineKeyboardMarkup? = null
) {
    client.api {
        executeAsync(
            SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode(parseMode)
                .replyParameters(replyParameters)
                .replyMarkup(replyMarkup)
                .build()
        )
    }
}

internal suspend fun editTextMessage(
    client: TelegramClient,
    chatId: Long,
    messageId: Int,
    text: String,
    replyMarkup: InlineKeyboardMarkup,
    parseMode: String? = null
) {
    client.api {
        executeAsync(
            EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(text)
                .parseMode(parseMode)
                .replyMarkup(replyMarkup)
                .build()
        )
    }
}

internal suspend fun answerCallbackQuery(
    client: TelegramClient,
    callbackQueryId: String,
    text: String? = null,
    showAlert: Boolean = false
) {
    client.api {
        executeAsync(
            AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .text(text)
                .showAlert(showAlert)
                .build()
        )
    }
}

internal suspend fun sendDocumentFile(
    client: TelegramClient,
    chatId: Long,
    bytes: ByteArray,
    filename: String,
    caption: String?,
    parseMode: String?,
    replyParameters: ReplyParameters?
) {
    client.api {
        executeAsync(
            SendDocument.builder()
                .chatId(chatId)
                .document(bytes.asInputFile(filename))
                .caption(caption)
                .parseMode(parseMode)
                .replyParameters(replyParameters)
                .build()
        )
    }
}

internal suspend fun sendAnimationFile(
    client: TelegramClient,
    chatId: Long,
    animation: InputFile,
    caption: String?,
    parseMode: String?,
    replyParameters: ReplyParameters?
) {
    client.api {
        executeAsync(
            SendAnimation.builder()
                .chatId(chatId)
                .animation(animation)
                .caption(caption)
                .parseMode(parseMode)
                .replyParameters(replyParameters)
                .build()
        )
    }
}

internal suspend fun sendMediaGroup(
    client: TelegramClient,
    chatId: Long,
    media: List<InputMedia>,
    replyParameters: ReplyParameters?
) {
    client.api {
        executeAsync(
            SendMediaGroup.builder()
                .chatId(chatId)
                .medias(media)
                .replyParameters(replyParameters)
                .build()
        )
    }
}

internal fun richMessageRequest(
    chatId: Long,
    markdown: String,
    replyParameters: ReplyParameters?
): SendRichMessage =
    SendRichMessage.builder()
        .chatId(chatId)
        .richMessage(InputRichMessage.builder().markdown(markdown).build())
        .replyParameters(replyParameters)
        .build()

// a fresh input stream per attempt: the previous attempt may have consumed the old one.
internal fun ByteArray.asInputFile(filename: String): InputFile =
    InputFile(ByteArrayInputStream(this), filename)
