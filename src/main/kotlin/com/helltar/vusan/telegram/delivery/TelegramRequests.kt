package com.helltar.vusan.telegram.delivery

import com.helltar.vusan.telegram.api

import java.io.ByteArrayInputStream
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.reactions.SetMessageReaction
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation
import org.telegram.telegrambots.meta.api.methods.send.SendSticker
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendRichMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditEphemeralMessageText
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.ReplyParameters
import org.telegram.telegrambots.meta.api.objects.ephemeral.EphemeralMessageParameters
import org.telegram.telegrambots.meta.api.objects.media.InputMedia
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionTypeEmoji
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.richtext.InputRichMessage
import org.telegram.telegrambots.meta.generics.TelegramClient

// the raw Bot API calls shared by TelegramOutputSender's send policy and TelegramSendFallbacks' rejection
// handling. nothing here decides what to try or what to do on failure.

/**
 * Where a message goes: the chat, and the forum topic in it when it has topics. They travel together because
 * naming only the chat lands the message in General instead of its conversation. [messageThreadId] is a topic
 * id only, never the reply-thread id telegram also sets in ordinary supergroups; see
 * `Message.forumTopicIdOrNull`.
 */
data class ChatTarget(val chatId: Long, val messageThreadId: Int? = null)

internal suspend fun sendTextMessage(
    client: TelegramClient,
    target: ChatTarget,
    text: String,
    parseMode: String?,
    replyParameters: ReplyParameters?,
    replyMarkup: InlineKeyboardMarkup? = null,
    // when set, only that one person sees the message, as with telegram's ephemeral messages
    ephemeral: EphemeralMessageParameters? = null,
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
                .ephemeralMessageParameters(ephemeral)
                .build(),
        )
    }
}

/**
 * A bot message a callback may edit: an ordinary one by id, or an ephemeral one by its recipient and its own
 * id, since ephemeral messages have no message id and their own edit methods.
 */
internal sealed interface EditableMessage {

    data class Regular(val messageId: Int) : EditableMessage

    data class Ephemeral(val receiverUserId: Long, val ephemeralMessageId: Int) : EditableMessage
}

internal suspend fun editTextMessage(
    client: TelegramClient,
    chatId: Long,
    message: EditableMessage,
    text: String,
    replyMarkup: InlineKeyboardMarkup?,
    parseMode: String? = null,
) {
    when (message) {
        is EditableMessage.Regular -> editTextMessage(client, chatId, message.messageId, text, replyMarkup, parseMode)
        is EditableMessage.Ephemeral ->
            client.api {
                executeAsync(
                    EditEphemeralMessageText.builder()
                        .chatId(chatId)
                        .receiverUserId(message.receiverUserId)
                        .ephemeralMessageId(message.ephemeralMessageId)
                        .text(text)
                        .parseMode(parseMode)
                        .replyMarkup(replyMarkup)
                        .build(),
                )
            }
    }
}

// a null [replyMarkup] removes the buttons rather than keeping them, which is what ends a live status bubble.
// the edited message, as telegram answers for one the bot sent itself
internal suspend fun editTextMessage(
    client: TelegramClient,
    chatId: Long,
    messageId: Int,
    text: String,
    replyMarkup: InlineKeyboardMarkup?,
    parseMode: String? = null,
): Message? =
    client.api {
        executeAsync(
            EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(text)
                .parseMode(parseMode)
                .replyMarkup(replyMarkup)
                .build(),
        )
    } as? Message

// the live status bubble: silent because it is not news, and returned whole: its id serves every later edit
// and its removal, and its entities say whether the custom emoji in it went through.
internal suspend fun sendStatusMessage(
    client: TelegramClient,
    target: ChatTarget,
    text: String,
    parseMode: String?,
    replyParameters: ReplyParameters?,
    replyMarkup: InlineKeyboardMarkup?,
): Message =
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
                .build(),
        )
    }

internal suspend fun deleteChatMessage(client: TelegramClient, chatId: Long, messageId: Int) {
    client.api {
        executeAsync(
            DeleteMessage.builder()
                .chatId(chatId)
                .messageId(messageId)
                .build(),
        )
    }
}

/** Sets the bot's one reaction on a message, or removes it when [emoji] is `null`. */
internal suspend fun setMessageReaction(client: TelegramClient, chatId: Long, messageId: Int, emoji: String?) {
    client.api {
        executeAsync(
            SetMessageReaction.builder()
                .chatId(chatId)
                .messageId(messageId)
                .reactionTypes(listOfNotNull(emoji?.let { ReactionTypeEmoji.builder().emoji(it).build() }))
                .build(),
        )
    }
}

internal suspend fun answerCallbackQuery(
    client: TelegramClient,
    callbackQueryId: String,
    text: String? = null,
    showAlert: Boolean = false,
) {
    client.api {
        executeAsync(
            AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .text(text)
                .showAlert(showAlert)
                .build(),
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
    replyParameters: ReplyParameters?,
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
                .build(),
        )
    }
}

internal suspend fun sendAnimationFile(
    client: TelegramClient,
    target: ChatTarget,
    animation: InputFile,
    caption: String?,
    parseMode: String?,
    replyParameters: ReplyParameters?,
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
                .build(),
        )
    }
}

// stickers are always resent by file_id, and the Bot API takes no caption on one.
internal suspend fun sendStickerFile(
    client: TelegramClient,
    target: ChatTarget,
    fileId: String,
    replyParameters: ReplyParameters?,
) {
    client.api {
        executeAsync(
            SendSticker.builder()
                .chatId(target.chatId)
                .messageThreadId(target.messageThreadId)
                .sticker(InputFile(fileId))
                .replyParameters(replyParameters)
                .build(),
        )
    }
}

internal suspend fun sendMediaGroup(
    client: TelegramClient,
    target: ChatTarget,
    media: List<InputMedia>,
    replyParameters: ReplyParameters?,
) {
    client.api {
        executeAsync(
            SendMediaGroup.builder()
                .chatId(target.chatId)
                .messageThreadId(target.messageThreadId)
                .medias(media)
                .replyParameters(replyParameters)
                .build(),
        )
    }
}

internal fun richMessageRequest(
    target: ChatTarget,
    markdown: String,
    replyParameters: ReplyParameters?,
): SendRichMessage =
    SendRichMessage.builder()
        .chatId(target.chatId)
        .messageThreadId(target.messageThreadId)
        .richMessage(InputRichMessage.builder().markdown(markdown).build())
        .replyParameters(replyParameters)
        .build()

// a fresh stream per attempt: the previous attempt may have consumed the old one.
internal fun ByteArray.asInputFile(filename: String): InputFile =
    InputFile(ByteArrayInputStream(this), filename)
