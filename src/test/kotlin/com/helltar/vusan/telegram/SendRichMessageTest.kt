package com.helltar.vusan.telegram

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.telegram.telegrambots.meta.api.objects.ReplyParameters

// SendRichMessage is hand-rolled (telegrambots 10.0.0 does not model Bot API 10.1 rich messages),
// so its wire format is pinned here against the sendRichMessage / InputRichMessage spec.
class SendRichMessageTest {

    // the okhttp client serializes generic api methods with a default-configured ObjectMapper.
    private val mapper = ObjectMapper()

    @Test
    fun `serializes to the sendRichMessage wire format`() {
        val method =
            SendRichMessage(
                chatId = "42",
                richMessage = InputRichMessage("# Title\n\n- one"),
                replyParameters = ReplyParameters.builder().messageId(7).build()
            )

        val json = mapper.readTree(mapper.writeValueAsString(method))

        assertEquals("sendRichMessage", json["method"].asText())
        assertEquals("42", json["chat_id"].asText())
        assertEquals("# Title\n\n- one", json["rich_message"]["markdown"].asText())
        assertEquals(7, json["reply_parameters"]["message_id"].asInt())
    }

    // InputRichMessage must carry exactly one of html/markdown/blocks, and absent optional
    // fields must be omitted rather than serialized as null.
    @Test
    fun `sends only the markdown field and omits absent optionals`() {
        val json = mapper.readTree(mapper.writeValueAsString(SendRichMessage("42", InputRichMessage("hi"))))

        assertEquals(setOf("markdown"), json["rich_message"].fieldNames().asSequence().toSet())
        assertFalse(json.has("reply_parameters"))
    }
}
