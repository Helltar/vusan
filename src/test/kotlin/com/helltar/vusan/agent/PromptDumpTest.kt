package com.helltar.vusan.agent

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptDumpTest {

    private val requestMeta = RequestMetaInfo.create(KoogClock.System)
    private val responseMeta = ResponseMetaInfo.create(KoogClock.System)

    private fun promptOf(vararg messages: Message) =
        Prompt(messages = messages.toList(), id = "dump-test")

    @Test
    fun `every message is dumped in order with its role`() {
        val dump =
            renderPromptDump(
                prompt =
                    promptOf(
                        Message.System("you are a parrot", requestMeta),
                        Message.User("<sticker_catalog>#7 a waving cat</sticker_catalog>\n\nsend one", requestMeta),
                        Message.Assistant("here it is", responseMeta)
                    ),
                model = "test-model",
                tools = listOf("searchStickers", "sendSticker")
            )

        assertContains(dump, "model=[test-model] messages=3 tools=[searchStickers, sendSticker]")
        assertTrue(dump.indexOf("you are a parrot") < dump.indexOf("#7 a waving cat"))
        assertTrue(dump.indexOf("#7 a waving cat") < dump.indexOf("here it is"))
        assertContains(dump, "--- system ---")
        assertContains(dump, "--- user ---")
        assertContains(dump, "--- assistant ---")
    }

    @Test
    fun `tool calls and their results keep name, id and output`() {
        val call = MessagePart.Tool.Call(id = "c1", tool = "searchStickers", args = """{"query":"cat"}""")
        val result = MessagePart.Tool.Result(id = "c1", tool = "searchStickers", output = "#7 a waving cat")

        val dump =
            renderPromptDump(
                prompt =
                    promptOf(
                        Message.Assistant(parts = listOf(call), metaInfo = responseMeta),
                        Message.User(parts = listOf(result), metaInfo = requestMeta)
                    ),
                model = "test-model",
                tools = emptyList()
            )

        assertContains(dump, """[tool call searchStickers id=c1] {"query":"cat"}""")
        assertContains(dump, "[tool result searchStickers id=c1 error=false]")
        assertContains(dump, "#7 a waving cat")
    }

    @Test
    fun `binary attachments are reduced to their size`() {
        val attachment =
            MessagePart.Attachment(
                AttachmentSource.Image(
                    content = AttachmentContent.Binary.Bytes(ByteArray(64) { 7 }),
                    format = "png",
                    mimeType = "image/png",
                    fileName = "frame.png"
                )
            )

        val dump =
            renderPromptDump(
                prompt = promptOf(Message.User(parts = listOf(attachment), metaInfo = requestMeta)),
                model = "test-model",
                tools = emptyList()
            )

        assertContains(dump, "[attachment image/png name=frame.png] 64 bytes")
        assertFalse(dump.contains(Regex("[A-Za-z0-9+/]{40,}")))
    }
}
