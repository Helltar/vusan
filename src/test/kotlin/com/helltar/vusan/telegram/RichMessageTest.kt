package com.helltar.vusan.telegram

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates
import org.telegram.telegrambots.meta.api.objects.ReplyParameters
import org.telegram.telegrambots.meta.api.objects.richblock.RichBlockParagraph
import org.telegram.telegrambots.meta.api.objects.richtext.RichTextConcat
import org.telegram.telegrambots.meta.api.objects.richtext.RichTextPlain

// rich messages travel both ways: vusan sends `sendRichMessage`, and Telegram returns the sent
// message — and can deliver an inbound one — as `Message.rich_message`. telegrambots 10.1.0 could
// not deserialize the receiving side at all, and an unparsable update stalls getUpdates forever,
// so both directions are pinned here.
class RichMessageTest {

    // the okhttp client serializes api methods and parses responses with a default ObjectMapper.
    private val mapper = ObjectMapper()

    @Test
    fun `serializes to the sendRichMessage wire format`() {
        val method = richMessageRequest(42, "# Title\n\n- one", ReplyParameters.builder().messageId(7).build())

        val json = mapper.readTree(mapper.writeValueAsString(method))

        assertEquals("sendRichMessage", json["method"].asText())
        assertEquals("42", json["chat_id"].asText())
        assertEquals("# Title\n\n- one", json["rich_message"]["markdown"].asText())
        assertEquals(7, json["reply_parameters"]["message_id"].asInt())
    }

    // InputRichMessage must carry exactly one of html/markdown, and absent optional fields must be
    // omitted rather than serialized as null.
    @Test
    fun `sends only the markdown field and omits absent optionals`() {
        val json = mapper.readTree(mapper.writeValueAsString(richMessageRequest(42, "hi", null)))

        assertEquals(setOf("markdown"), json["rich_message"].fieldNames().asSequence().toSet())
        assertFalse(json.has("reply_parameters"))
    }

    // a failure here makes every sent rich message look rejected, duplicating it as a fallback file.
    @Test
    fun `parses the sent message returned by sendRichMessage`() {
        val response =
            """
            {"ok":true,"result":{"message_id":10,"date":1,"chat":{"id":42,"type":"private"},
            "rich_message":{"blocks":[{"type":"paragraph","text":"hello"}]}}}
            """.trimIndent()

        val message = richMessageRequest(42, "hello", null).deserializeResponse(response)

        val paragraph = message.richMessage.blocks.single() as RichBlockParagraph
        assertEquals("hello", (paragraph.text as RichTextPlain).text)
    }

    // an inbound rich message must never stall getUpdates: unknown block and text types have to
    // degrade to null instead of failing the whole update.
    @Test
    fun `parses an inbound rich message with plain, nested and unknown parts`() {
        val response =
            """
            {"ok":true,"result":[{"update_id":1,"message":{"message_id":11,"date":1,
            "chat":{"id":42,"type":"private"},
            "from":{"id":7,"is_bot":false,"first_name":"a"},
            "rich_message":{"blocks":[
              {"type":"paragraph","text":["plain ",{"type":"bold","text":"bold"}]},
              {"type":"paragraph","text":{"type":"future_text","text":"x"}},
              {"type":"future_block","text":"x"}
            ]}}}]}
            """.trimIndent()

        val blocks = GetUpdates.builder().build().deserializeResponse(response).single().message.richMessage.blocks

        val nested = (blocks[0] as RichBlockParagraph).text as RichTextConcat
        assertEquals("plain ", (nested.texts.first() as RichTextPlain).text)
        assertNotNull(blocks[1])
        assertEquals(null, (blocks[1] as RichBlockParagraph).text)
        assertEquals(null, blocks[2])
    }
}
