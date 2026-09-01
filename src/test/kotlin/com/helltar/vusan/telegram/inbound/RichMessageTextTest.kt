package com.helltar.vusan.telegram.inbound

import com.fasterxml.jackson.databind.ObjectMapper
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient

private const val BOT_USER_ID = 100L
private const val BOT_USERNAME = "VusanBot"

class RichMessageTextTest {

    private val mapper = ObjectMapper()

    // the replied message carries no audio, so the summary must never reach for the api.
    private val unusedClient =
        Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java)
        ) { _, method, _ -> error("unexpected telegram call: ${method.name}") } as TelegramClient

    private fun message(json: String): Message = mapper.readValue(json, Message::class.java)

    private fun richMessage(blocks: String, extra: String = ""): Message =
        message("""{"message_id":10,"date":1,"chat":{"id":42,"type":"private"},$extra"rich_message":{"blocks":[$blocks]}}""")

    @Test
    fun `flattens a rich message back into rich markdown`() {
        val message =
            richMessage(
                """
                {"type":"heading","text":"Release notes","size":2},
                {"type":"paragraph","text":["Ship ",{"type":"bold","text":"v2"},
                  " with ",{"type":"code","text":"--fast"},
                  ", see ",{"type":"url","text":"the docs","url":"https://example.com"}]},
                {"type":"list","items":[
                  {"label":"1.","blocks":[{"type":"paragraph","text":"first"}]},
                  {"label":"-","blocks":[{"type":"paragraph","text":"done"}],"has_checkbox":true,"is_checked":true}]},
                {"type":"blockquote","blocks":[{"type":"paragraph","text":"quoted line"}],"credit":"The Author"},
                {"type":"pre","text":"print(1)","language":"python"},
                {"type":"divider"},
                {"type":"photo","photo":[{"file_id":"a","file_unique_id":"b","width":1,"height":1}],
                 "caption":{"text":"the chart"}},
                {"type":"future_block","text":"from a newer bot api"}
                """.trimIndent()
            )

        assertEquals(
            """
            ## Release notes

            Ship **v2** with `--fast`, see [the docs](https://example.com)

            1. first
            - [x] done

            > quoted line
            — The Author

            ```python
            print(1)
            ```

            ---

            the chart
            """.trimIndent(),
            message.richMessage.toRichMarkdown()
        )
    }

    @Test
    fun `renders a table with its header separator and caption`() {
        val message =
            richMessage(
                """
                {"type":"table","cells":[
                  [{"text":"Metric","is_header":true,"align":"left","valign":"top"},
                   {"text":"Value","is_header":true,"align":"right","valign":"top"}],
                  [{"text":"Speed","align":"left","valign":"top"},
                   {"text":"42","align":"right","valign":"top"}]],
                 "caption":"Benchmarks"}
                """.trimIndent()
            )

        assertEquals(
            """
            | Metric | Value |
            | --- | --- |
            | Speed | 42 |

            Benchmarks
            """.trimIndent(),
            message.richMessage.toRichMarkdown()
        )
    }

    @Test
    fun `an unreadable rich message renders as no text at all`() {
        val message = richMessage("""{"type":"future_block","text":"only unknown blocks"}""")

        assertEquals("", message.richMessage.toRichMarkdown())
        assertEquals(null, message.textSnippetOrNull())
    }

    @Test
    fun `a rich message is typed and snippeted like any other content`() {
        val message = richMessage("""{"type":"paragraph","text":"hello"}""")

        assertEquals("rich message", message.contentTypeName())
        assertEquals("hello", message.textSnippetOrNull())
    }

    @Test
    fun `replying to a rich message quotes it with its layout`() {
        val replied =
            richMessage(
                """{"type":"heading","text":"Plan","size":1},{"type":"paragraph","text":"step one"}""",
                extra = """"reply_to_message":{"message_id":9,"date":1,"chat":{"id":42,"type":"private"},"text":"x"},"""
            )

        // the rich message is the replied-to one, so it is nested under `reply_to_message`.
        val message =
            message(
                """
                {"message_id":11,"date":1,"chat":{"id":42,"type":"private"},"text":"what is step two?",
                 "reply_to_message":${mapper.writeValueAsString(replied)}}
                """.trimIndent()
            )

        val summary =
            runBlocking { message.replySummaryOrNull(unusedClient, voiceTranscriber = null, botUserId = BOT_USER_ID) }

        assertEquals("rich message", summary?.type)
        assertEquals("# Plan\n\nstep one", summary?.textOrCaption)
    }

    @Test
    fun `a group rich message is handled only when it mentions or replies to the bot`() {
        val group = """"chat":{"id":-42,"type":"supergroup"},"""

        val mentioning =
            message(
                """{"message_id":1,"date":1,$group"rich_message":{"blocks":[
                   {"type":"paragraph","text":["hey ",
                    {"type":"mention","text":"@VusanBot","username":"VusanBot"}," look"]}]}}"""
            )

        val textMentioning =
            message(
                """{"message_id":2,"date":1,$group"rich_message":{"blocks":[
                   {"type":"paragraph","text":{"type":"text_mention","text":"Vusan",
                    "user":{"id":100,"is_bot":true,"first_name":"Vusan"}}}]}}"""
            )

        val unrelated =
            message("""{"message_id":3,"date":1,$group"rich_message":{"blocks":[{"type":"paragraph","text":"hi all"}]}}""")

        assertTrue(shouldHandle(mentioning, BOT_USER_ID, BOT_USERNAME))
        assertTrue(shouldHandle(textMentioning, BOT_USER_ID, BOT_USERNAME))
        assertFalse(shouldHandle(unrelated, BOT_USER_ID, BOT_USERNAME))
    }
}
