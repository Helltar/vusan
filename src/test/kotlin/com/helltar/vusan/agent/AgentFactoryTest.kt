package com.helltar.vusan.agent

import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.ToolResultKind
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.utils.time.KoogClock
import com.helltar.vusan.agent.conversation.toolCallArgsForStorage
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.tools.poll.PollTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentFactoryTest {

    private val meta = ResponseMetaInfo.create(KoogClock.System)

    private fun assistant(vararg parts: MessagePart.ResponsePart) =
        Message.Assistant(parts = parts.toList(), metaInfo = meta)

    private fun user(text: String) =
        Message.User(parts = listOf(MessagePart.Text(text)), metaInfo = RequestMetaInfo.create(KoogClock.System))

    @Test
    fun `empty assistant message delivered nothing`() {
        assertTrue(assistant().deliveredNothing())
    }

    @Test
    fun `blank text delivered nothing`() {
        assertTrue(assistant(MessagePart.Text("   \n ")).deliveredNothing())
    }

    @Test
    fun `non-blank text counts as a deliverable caption`() {
        assertFalse(assistant(MessagePart.Text("here you go")).deliveredNothing())
    }

    @Test
    fun `a pending tool call is not nothing`() {
        val call = MessagePart.Tool.Call(id = "1", tool = "sendMessage", args = """{"text":"hi"}""")
        assertFalse(assistant(call).deliveredNothing())
    }

    @Test
    fun `a tool call alongside blank text is not nothing`() {
        val call = MessagePart.Tool.Call(id = "1", tool = "webSearch", args = "{}")
        assertFalse(assistant(MessagePart.Text(""), call).deliveredNothing())
    }

    @Test
    fun `trailing empty assistant is dropped before the nudge re-request`() {
        val turn = user("ok then")
        assertEquals(listOf(turn), listOf(turn, assistant()).withoutTrailingEmptyAssistant())
    }

    @Test
    fun `assistant with text is kept`() {
        val messages = listOf(user("ok then"), assistant(MessagePart.Text("ok")))
        assertEquals(messages, messages.withoutTrailingEmptyAssistant())
    }

    @Test
    fun `assistant with blank text is kept - it still serializes to string content`() {
        val messages = listOf(user("ok then"), assistant(MessagePart.Text("")))
        assertEquals(messages, messages.withoutTrailingEmptyAssistant())
    }

    @Test
    fun `assistant with a tool call is kept`() {
        val call = MessagePart.Tool.Call(id = "1", tool = "setReaction", args = """{"emoji":"😉"}""")
        val messages = listOf(user("ok then"), assistant(call))
        assertEquals(messages, messages.withoutTrailingEmptyAssistant())
    }

    @Test
    fun `a run with iterations to spare keeps calling tools`() {
        assertFalse(outOfToolBudget(iterations = 2, maxIterations = 60))
        assertFalse(outOfToolBudget(iterations = 55, maxIterations = 60))
    }

    @Test
    fun `the last iterations are reserved for the wrap-up`() {
        assertTrue(outOfToolBudget(iterations = 56, maxIterations = 60))
        assertTrue(outOfToolBudget(iterations = 60, maxIterations = 60))
    }

    // a tool round costs two iterations, so the check may first see an already thinned budget; whenever it
    // does, the wrap-up request and nodeFinish must both still fit under the limit.
    @Test
    fun `landing always leaves room for the wrap-up request and the finish node`() {
        val maxIterations = 60
        val landing = (1..maxIterations).first { outOfToolBudget(it, maxIterations) }

        assertTrue(maxIterations - landing >= 2)
    }

    @Test
    fun `only the trailing empty assistant is dropped`() {
        val earlier = listOf(user("hello"), assistant(MessagePart.Text("earlier reply")), user("ok then"))
        assertEquals(earlier, (earlier + assistant()).withoutTrailingEmptyAssistant())
    }

    // koog fills `parts` for every tool that ran, and that is what reaches the LLM, so a cap read off
    // `output` alone bounds nothing.
    @Test
    fun `a tool result is bounded through the parts the model actually receives`() {
        val long = "x".repeat(5_000)
        val bounded = toolResult(output = long, parts = listOf(MessagePart.Text(long))).boundedForLiveContext(500)

        assertEquals(1_500, bounded.output.length)
        assertEquals(1_500, (bounded.parts?.single() as MessagePart.Text).text.length)
    }

    @Test
    fun `the budget is spent on the text the model receives, not on the unused output`() {
        val result = toolResult(output = "x".repeat(300), parts = listOf(MessagePart.Text("y".repeat(30))))

        assertEquals(10, result.liveContextTokens)
        assertEquals(100, toolResult(output = "x".repeat(300), parts = null).liveContextTokens)
    }

    // the estimator reads bytes, so the same number of characters is not the same cost, and a budget
    // spent in characters charged cyrillic half of what it puts in the prompt.
    @Test
    fun `cyrillic costs the run budget more than latin of the same length`() {
        val latin = toolResult(output = "a".repeat(300), parts = listOf(MessagePart.Text("a".repeat(300))))
        val cyrillic = toolResult(output = "я".repeat(300), parts = listOf(MessagePart.Text("я".repeat(300))))

        assertEquals(100, latin.liveContextTokens)
        assertEquals(200, cyrillic.liveContextTokens)
    }

    @Test
    fun `the same token budget buys fewer cyrillic characters than latin ones`() {
        val latin = toolResult(output = "a".repeat(3_000), parts = null).boundedForLiveContext(500)
        val cyrillic = toolResult(output = "я".repeat(3_000), parts = null).boundedForLiveContext(500)

        assertEquals(1_500, latin.output.length)
        assertEquals(750, cyrillic.output.length)
    }

    @Test
    fun `a tool result within the cap is left alone`() {
        val result = toolResult(output = "short", parts = listOf(MessagePart.Text("short")))

        assertEquals(result, result.boundedForLiveContext(500))
    }

    // an image cannot be shortened, only dropped, and dropping it would answer a different question.
    @Test
    fun `a non-text part survives the cap untouched`() {
        val image =
            MessagePart.Attachment(
                AttachmentSource.Image(AttachmentContent.URL("https://example.invalid/a.png"), format = "png")
            )
        val long = "x".repeat(5_000)

        val bounded = toolResult(output = long, parts = listOf(image, MessagePart.Text(long))).boundedForLiveContext(500)

        assertEquals(image, bounded.parts?.first())
        assertEquals(1_500, (bounded.parts?.last() as MessagePart.Text).text.length)
    }

    @Test
    fun `a cap too small for the truncation notice omits the result instead`() {
        val bounded = toolResult(output = "x".repeat(300), parts = listOf(MessagePart.Text("x".repeat(300))))
            .boundedForLiveContext(5)

        assertTrue(bounded.output.startsWith("[tool result omitted"))
        assertTrue((bounded.parts?.single() as MessagePart.Text).text.startsWith("[tool result omitted"))
    }

    // koog's JSONObject.toString() escapes nothing, and the storage layer drops anything it cannot
    // parse — so a quoted shell argument used to leave history with `{}` and no record of the call.
    @Test
    fun `tool args survive a quote on the way into history`() {
        val command = """printf "sample""""
        val json = JSONObject(mapOf("command" to JSONPrimitive(command))).toToolArgsJson()

        assertEquals(json, toolCallArgsForStorage(json), "the storage layer could not parse the args back")
        assertEquals(command, Json.parseToJsonElement(json).jsonObject.getValue("command").jsonPrimitive.content)
    }

    @Test
    fun `tool args survive a backslash and a newline too`() {
        val args = JSONObject(mapOf("path" to JSONPrimitive("""C:\tmp"""), "text" to JSONPrimitive("one\ntwo")))

        val json = args.toToolArgsJson()

        assertEquals(json, toolCallArgsForStorage(json))
        assertTrue(json.contains("""C:\\tmp"""), "backslash was not escaped: $json")
        assertTrue(json.contains("""one\ntwo"""), "newline was not escaped: $json")
    }

    // the guard exists for one observed shape: a sibling call in a garbled parallel batch arriving with
    // no arguments at all.
    @Test
    fun `a call with no arguments at all is turned away`() {
        val call = MessagePart.Tool.Call(id = "c1", tool = "createPoll", args = "{}")

        assertEquals(
            listOf("question", "options", "isAnonymous", "allowsMultipleAnswers"),
            call.missingRequiredArgs(pollRegistry)
        )
    }

    // koog's generated schema marks kotlin-defaulted parameters required, and koog itself decodes the
    // call into their defaults, so leaving them out is an ordinary call and not a garbled one.
    @Test
    fun `a call that omits only defaulted arguments runs`() {
        val call =
            MessagePart.Tool.Call(
                id = "c2",
                tool = "createPoll",
                args = """{"question":"Tea or coffee?","options":["tea","coffee"]}"""
            )

        assertEquals(emptyList(), call.missingRequiredArgs(pollRegistry))
    }

    @Test
    fun `a call to a tool the registry does not have is left to koog`() {
        val call = MessagePart.Tool.Call(id = "c3", tool = "noSuchTool", args = "{}")

        assertEquals(emptyList(), call.missingRequiredArgs(pollRegistry))
    }

    private val pollRegistry = ToolRegistry { tools(PollTools(BotOutbox())) }

    private fun toolResult(output: String, parts: List<MessagePart.ContentPart>?) =
        ReceivedToolResult(
            id = "call-1",
            tool = "searchWeb",
            toolArgs = JSONObject(emptyMap()),
            toolDescription = null,
            output = output,
            resultKind = ToolResultKind.Success,
            result = null,
            parts = parts
        )
}
