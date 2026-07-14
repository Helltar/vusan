package com.helltar.vusan.telegram

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethodMessage
import org.telegram.telegrambots.meta.api.objects.ReplyParameters

// telegrambots 10.0.0 does not model `sendRichMessage` yet, so this is a hand-rolled method
// covering the markdown flavor vusan sends; jackson serializes it exactly like the built-in
// json-only methods, so it goes through the regular `TelegramClient.execute` path.
@JsonInclude(JsonInclude.Include.NON_NULL)
internal class SendRichMessage(
    @get:JsonProperty("chat_id")
    val chatId: String,
    @get:JsonProperty("rich_message")
    val richMessage: InputRichMessage,
    @get:JsonProperty("reply_parameters")
    val replyParameters: ReplyParameters? = null
) : BotApiMethodMessage() {

    override fun getMethod(): String = "sendRichMessage"
}

internal class InputRichMessage(
    @get:JsonProperty("markdown")
    val markdown: String
)
