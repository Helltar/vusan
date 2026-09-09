package com.helltar.vusan.telegram.delivery

import com.helltar.vusan.telegram.api

import java.io.ByteArrayInputStream
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation
import org.telegram.telegrambots.meta.api.methods.send.SendSticker
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendRichMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.ReplyParameters
import org.telegram.telegrambots.meta.api.objects.media.InputMedia
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.richtext.InputRichMessage
import org.telegram.telegrambots.meta.generics.TelegramClient

// the raw Bot API calls shared by the send policy in TelegramOutputSender and the rejection
// handling in TelegramSendFallbacks. nothing here decides what to try or what to do on failure.

/**
 * Where a message goes: the chat, and the forum topic inside it when the chat has topics.
 *
 * The two travel together because naming only the chat is not a smaller instruction, it is a
 * different one — the message lands in the group's General topic instead of the conversation it
 * belongs to. [messageThreadId] is a topic id only, never the thread id Telegram also puts on
 * replies in ordinary supergroups; see `Message.forumTopicIdOrNull`.
 */
data class ChatTarget(val chatId: Long, val messageThreadId: Int? = null)

internal suspend fun sendTextMessage(
    client: TelegramClient,
    target: ChatTarget,
    text: String,
    parseMode: String?,
    replyParameters: ReplyParameters?,
    replyMarkup: InlineKeyboardMarkup? = null
) {
    client.api {
        executeAsync(
            SendMessage.builder()
                .chatId(target.chatId)
                .messageThreadId(target.messageThreadId)
                .text(text)
                .parseMode(parseMode)
                .replyParameters(replyParameters)
                .replyMarkup(replyMarkup)
                .build()
        )
    }
}

// a null [replyMarkup] does not keep the buttons the message has — it takes them off, which is what
// ends a live status bubble.
internal suspend fun editTextMessage(
    client: TelegramClient,
    chatId: Long,
    messageId: Int,
    text: String,
    replyMarkup: InlineKeyboardMarkup?,
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

// the live status bubble: sent silently because it is not news, and returning its id because every
// later edit and its removal address it.
internal suspend fun sendStatusMessage(
    client: TelegramClient,
    target: ChatTarget,
    text: String,
    parseMode: String?,
    replyParameters: ReplyParameters?,
    replyMarkup: InlineKeyboardMarkup?
): Int =
    client.api {
        executeAsync(
            SendMessage.builder()
                .chatId(target.chatId)
                .messageThreadId(target.messageThreadId)
                .text(text)
                .parseMode(parseMode)
                .replyParameters(replyParameters)
                .replyMarkup(replyMarkup)
                .disableNotification(true)
                .build()
        )
    }.messageId

internal suspend fun deleteChatMessage(client: TelegramClient, chatId: Long, messageId: Int) {
    client.api {
        executeAsync(
            DeleteMessage.builder()
                .chatId(chatId)
                .messageId(messageId)
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
    target: ChatTarget,
    bytes: ByteArray,
    filename: String,
    caption: String?,
    parseMode: String?,
    replyParameters: ReplyParameters?
) {
    client.api {
        executeAsync(
            SendDocument.builder()
                .chatId(target.chatId)
                .messageThreadId(target.messageThreadId)
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
    target: ChatTarget,
    animation: InputFile,
    caption: String?,
    parseMode: String?,
    replyParameters: ReplyParameters?
) {
    client.api {
        executeAsync(
            SendAnimation.builder()
                .chatId(target.chatId)
                .messageThreadId(target.messageThreadId)
                .animation(animation)
                .caption(caption)
                .parseMode(parseMode)
                .replyParameters(replyParameters)
                .build()
        )
    }
}

// stickers are always resent by file_id from the catalog, and the Bot API takes no caption on one.
internal suspend fun sendStickerFile(
    client: TelegramClient,
    target: ChatTarget,
    fileId: String,
    replyParameters: ReplyParameters?
) {
    client.api {
        executeAsync(
            SendSticker.builder()
                .chatId(target.chatId)
                .messageThreadId(target.messageThreadId)
                .sticker(InputFile(fileId))
                .replyParameters(replyParameters)
                .build()
        )
    }
}

internal suspend fun sendMediaGroup(
    client: TelegramClient,
    target: ChatTarget,
    media: List<InputMedia>,
    replyParameters: ReplyParameters?
) {
    client.api {
        executeAsync(
            SendMediaGroup.builder()
                .chatId(target.chatId)
                .messageThreadId(target.messageThreadId)
                .medias(media)
                .replyParameters(replyParameters)
                .build()
        )
    }
}

internal fun richMessageRequest(
    target: ChatTarget,
    markdown: String,
    replyParameters: ReplyParameters?
): SendRichMessage =
    SendRichMessage.builder()
        .chatId(target.chatId)
        .messageThreadId(target.messageThreadId)
        .richMessage(InputRichMessage.builder().markdown(markdown).build())
        .replyParameters(replyParameters)
        .build()

// a fresh input stream per attempt: the previous attempt may have consumed the old one.
internal fun ByteArray.asInputFile(filename: String): InputFile =
    InputFile(ByteArrayInputStream(this), filename)
