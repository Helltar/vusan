package com.helltar.vusan.telegram.delivery

import com.helltar.vusan.telegram.api
import java.io.Serializable
import org.telegram.telegrambots.meta.api.methods.send.SendSharedWidget
import org.telegram.telegrambots.meta.api.methods.send.SendWidget
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageWidgetData
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditSharedWidgetData
import org.telegram.telegrambots.meta.api.methods.widget.AnswerWidgetQuery
import org.telegram.telegrambots.meta.api.objects.ReplyParameters
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient

// the raw widget calls of Bot API 10.4. nothing here decides what to try or what to do on failure.

/** A widget telegram created: its message, and the shared widget's id when it was sent as one. */
data class SentWidget(val messageId: Int, val widgetId: String?) : Serializable {

    private companion object {
        private const val serialVersionUID = 1L
    }
}

internal suspend fun sendWidget(
    client: TelegramClient,
    target: ChatTarget,
    html: String,
    caption: String,
    width: Int,
    height: Int,
    chatData: String?,
    publicData: String?,
    allowQueries: Boolean,
    shared: Boolean,
    replyParameters: ReplyParameters?,
): SentWidget =
    client.api {
        executeAsync(
            SendWidget.builder()
                .chatId(target.chatId)
                .messageThreadId(target.messageThreadId)
                .html(html)
                .caption(caption)
                .width(width.takeIf { it > 0 })
                .height(height.takeIf { it > 0 })
                .chatData(chatData)
                .publicData(publicData)
                .allowQueries(allowQueries.takeIf { it })
                .isShared(shared.takeIf { it })
                .replyParameters(replyParameters)
                .build(),
        )
    }.toSentWidget()

internal suspend fun sendSharedWidget(
    client: TelegramClient,
    target: ChatTarget,
    widgetId: String,
    caption: String,
    chatData: String?,
    replyParameters: ReplyParameters?,
): SentWidget =
    client.api {
        executeAsync(
            SendSharedWidget.builder()
                .chatId(target.chatId)
                .messageThreadId(target.messageThreadId)
                .widgetId(widgetId)
                .caption(caption)
                .chatData(chatData)
                .replyParameters(replyParameters)
                .build(),
        )
    }.toSentWidget()

/** Changes one widget message's state; `null` leaves a field as it is, an empty string clears it. */
internal suspend fun editMessageWidgetData(
    client: TelegramClient,
    chatId: Long,
    messageId: Int,
    chatData: String?,
    publicData: String?,
) {
    client.api {
        executeAsync(
            EditMessageWidgetData.builder()
                .chatId(chatId)
                .messageId(messageId)
                .chatData(chatData)
                .publicData(publicData)
                .build(),
        )
    }
}

/** Changes a shared widget's shared state in every message and forward showing it. */
internal suspend fun editSharedWidgetData(
    client: TelegramClient,
    widgetId: String,
    publicData: String,
    cacheTimeSeconds: Int?,
) {
    client.api {
        executeAsync(
            EditSharedWidgetData.builder()
                .widgetId(widgetId)
                .publicData(publicData)
                .cacheTime(cacheTimeSeconds)
                .build(),
        )
    }
}

/** Answers a press inside a widget; [data] reaches only the widget that asked, `null` answers with nothing. */
internal suspend fun answerWidgetQuery(client: TelegramClient, queryId: String, data: String?) {
    client.api { executeAsync(AnswerWidgetQuery.builder().widgetQueryId(queryId).data(data).build()) }
}

private fun Message.toSentWidget(): SentWidget = SentWidget(messageId, widget?.id?.takeIf { it.isNotEmpty() })
