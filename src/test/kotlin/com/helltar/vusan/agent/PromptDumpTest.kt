package com.helltar.vusan.agent

import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptDumpTest {

    @Test
    fun `every message is dumped in order with its role`() {
        val dump =
            renderPromptDump(
                messages =
                    listOf(
                        Message.System("you are a parrot"),
                        Message.User("<sticker_catalog>#7 a waving cat</sticker_catalog>\n\nsend one"),
                        Message.Assistant("here it is"),
                    ),
                model = "test-model",
                tools = listOf("searchStickers", "sendSticker"),
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
        val call = Part.ToolCall(id = "c1", name = "searchStickers", arguments = buildJsonObject { put("query", "cat") })
        val result = ToolResult(callId = "c1", name = "searchStickers", output = "#7 a waving cat")

        val dump =
            renderPromptDump(
                messages = listOf(Message.Assistant(listOf(call)), Message.ToolResults(listOf(result))),
                model = "test-model",
                tools = emptyList(),
            )

        assertContains(dump, """[tool call searchStickers id=c1] {"query":"cat"}""")
        assertContains(dump, "[tool result searchStickers id=c1 error=false]")
        assertContains(dump, "#7 a waving cat")
    }

    // an image is megabytes of base64 and a reasoning block is the provider's; neither belongs in a log
    @Test
    fun `images are reduced to their size and reasoning to its presence`() {
        val image = Part.Image(ByteArray(64) { 7 }, "image/png", "frame.png")
        val reasoning = Part.Reasoning("https://api.openai.com/v1/responses", buildJsonObject { put("encrypted_content", "x".repeat(200)) })

        val dump =
            renderPromptDump(
                messages = listOf(Message.User(listOf(image)), Message.Assistant(listOf(reasoning, Part.Text("done")))),
                model = "test-model",
                tools = emptyList(),
            )

        assertContains(dump, "[image image/png name=frame.png] 64 bytes")
        assertContains(dump, "[reasoning from https://api.openai.com/v1/responses]")
        assertFalse(dump.contains(Regex("[A-Za-z0-9+/]{40,}")))
    }
}
