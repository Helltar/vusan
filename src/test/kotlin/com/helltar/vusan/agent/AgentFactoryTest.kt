package com.helltar.vusan.agent

import com.helltar.vusan.agent.conversation.ChatRole
import com.helltar.vusan.agent.conversation.ChatTurn
import com.helltar.vusan.agent.conversation.PromptConversation
import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmProvider
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.ScriptedLlmClient
import com.helltar.vusan.llm.TEST_MODEL
import com.helltar.vusan.llm.TokenUsage
import com.helltar.vusan.llm.textReply
import com.helltar.vusan.llm.toolCallReply
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.testScope
import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolCatalog
import com.helltar.vusan.tools.ToolGroup
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.suspendToolGuard
import com.helltar.vusan.tools.toolCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Suppress("unused")
private class ProbeTools(private val outbox: BotOutbox) : ToolSet {

    @Tool("Sends text to the user.")
    fun sendMessage(@Arg("The text.") text: String): String {
        outbox.enqueueText(text)
        return "Delivered."
    }

    @Tool("Looks something up.")
    fun lookUp(@Arg("What.") query: String): String = "found: $query"

    @Tool("Always fails.")
    suspend fun explode(@Arg("Why.") reason: String): String = suspendToolGuard { error("boom: $reason") }
}

@Suppress("unused")
private class DrawTools : ToolSet {

    @Tool("Draws a picture.")
    fun draw(@Arg("What.") subject: String): String = "drew $subject"
}

class AgentFactoryTest {

    private fun call(name: String, id: String = "c-$name", vararg args: Pair<String, String>) =
        Part.ToolCall(id, name, buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

    private class Run(
        val client: ScriptedLlmClient,
        val outbox: BotOutbox = BotOutbox(),
        val events: MutableList<ToolEvent> = mutableListOf(),
        val usages: MutableList<TokenUsage> = mutableListOf(),
    )

    private fun run(
        vararg replies: com.helltar.vusan.llm.Reply,
        maxModelCalls: Int = 20,
        history: List<ChatTurn> = emptyList(),
        mayStaySilent: Boolean = false,
        catalog: (BotOutbox) -> ToolCatalog = { outbox -> toolCatalog { tools(ProbeTools(outbox)); tools(ToolGroup.IMAGE_GENERATION, DrawTools()) } },
        block: (Run, String) -> Unit,
    ) = runBlocking {
        val run = Run(ScriptedLlmClient(*replies))
        val factory = AgentFactory(run.client, TEST_MODEL, RequestOptions(promptCacheKey = "vusan"), maxModelCalls = maxModelCalls)
        val tools = catalog(run.outbox)
        val preparation = factory.prepare(tools, "the request")
        val budget = TurnToolBudget(factory.liveToolResultMaxTokens)

        val answer =
            factory.build(
                scope = testScope(),
                conversation = PromptConversation(summary = null, turns = history),
                preparation = preparation,
                outbox = run.outbox,
                toolBudget = budget,
                toolEvents = run.events::add,
                tokenUsage = run.usages::add,
                mayStaySilent = mayStaySilent,
            ).run("the request")

        block(run, answer)
    }

    private val ChatRequest.toolNames: List<String>
        get() = tools.map { it.name }

    @Test
    fun `a plain answer ends the turn after one call`() =
        run(textReply("hello there")) { run, answer ->
            assertEquals("hello there", answer)
            assertEquals(1, run.client.requests.size)

            val request = run.client.requests.single()
            assertIs<Message.System>(request.messages.first())
            assertEquals("the request", (request.messages.last() as Message.User).text)
            assertEquals("vusan-${testScope().toString().hashCode().toUInt().toString(16)}", request.options.promptCacheKey)
        }

    @Test
    fun `a tool call is run and its result answered before the next call`() =
        run(toolCallReply(call("lookUp", args = arrayOf("query" to "cats"))), textReply("cats are fine")) { run, answer ->
            assertEquals("cats are fine", answer)
            assertEquals(2, run.client.requests.size)

            val results = assertIs<Message.ToolResults>(run.client.requests[1].messages.last())
            assertEquals("found: cats", results.results.single().output)
            assertEquals("c-lookUp", results.results.single().callId)
            assertFalse(results.results.single().isError)

            val event = run.events.single()
            assertEquals("lookUp", event.toolName)
            assertEquals("""{"query":"cats"}""", event.args)
        }

    @Test
    fun `a tool that fails answers the model with its message and is recorded as an error`() =
        run(toolCallReply(call("explode", args = arrayOf("reason" to "test"))), textReply("sorry")) { run, _ ->
            val result = assertIs<Message.ToolResults>(run.client.requests[1].messages.last()).results.single()

            assertTrue(result.isError)
            assertContains(result.output, "boom: test")
            assertTrue(run.events.single().isError)
        }

    // the guard exists for one observed shape: a sibling call in a garbled parallel batch arriving with
    // no arguments at all, for a tool that takes them
    @Test
    fun `a call with no arguments at all is turned away without running`() =
        run(toolCallReply(call("lookUp")), textReply("ok")) { run, _ ->
            val result = assertIs<Message.ToolResults>(run.client.requests[1].messages.last()).results.single()

            assertTrue(result.isError)
            assertContains(result.output, "takes: query")
        }

    @Test
    fun `a call to a tool nobody registered is answered with the names that exist`() =
        run(toolCallReply(call("noSuchTool")), textReply("ok")) { run, _ ->
            val result = assertIs<Message.ToolResults>(run.client.requests[1].messages.last()).results.single()

            assertTrue(result.isError)
            assertContains(result.output, "lookUp")
        }

    @Test
    fun `a group loaded mid-turn is offered from the next request on`() =
        run(toolCallReply(call("loadTools", args = arrayOf("groups" to "image_generation"))), toolCallReply(call("draw", args = arrayOf("subject" to "a cat"))), textReply("done")) { run, _ ->
            assertFalse("draw" in run.client.requests[0].toolNames)
            assertTrue("draw" in run.client.requests[1].toolNames)
            assertEquals("drew a cat", assertIs<Message.ToolResults>(run.client.requests[2].messages.last()).results.single().output)
        }

    @Test
    fun `an empty reply with nothing delivered is nudged once`() =
        run(textReply(""), textReply("here it is")) { run, answer ->
            assertEquals("here it is", answer)
            assertEquals(2, run.client.requests.size)

            val second = run.client.requests[1].messages
            assertFalse(second.any { it is Message.Assistant && it.parts.isEmpty() }, "the empty assistant is dropped before the nudge")
            assertContains((second.last() as Message.User).text, "Deliver your answer now")
        }

    @Test
    fun `an empty reply that stays empty after the nudge ends the turn`() =
        run(textReply(""), textReply("")) { run, answer ->
            assertEquals("", answer)
            assertEquals(2, run.client.requests.size)
        }

    @Test
    fun `an empty reply after a delivery tool is not nudged`() =
        run(toolCallReply(call("sendMessage", args = arrayOf("text" to "hi"))), textReply("")) { run, answer ->
            assertEquals("", answer)
            assertEquals(2, run.client.requests.size)
            assertTrue(run.outbox.hasQueuedOutput)
        }

    @Test
    fun `a turn allowed to stay silent may end empty on its first reply only`() =
        run(textReply(""), mayStaySilent = true) { run, _ -> assertEquals(1, run.client.requests.size) }

    @Test
    fun `a turn allowed to stay silent is still nudged after a tool round`() =
        run(toolCallReply(call("lookUp", args = arrayOf("query" to "x"))), textReply(""), textReply("found it"), mayStaySilent = true) { run, answer ->
            assertEquals("found it", answer)
            assertEquals(3, run.client.requests.size)
        }

    // the last model call is the wrap-up's: it carries no tools, and its text goes to the outbox
    @Test
    fun `a turn that runs out of model calls is landed with a tool-free wrap-up`() =
        run(
            toolCallReply(call("lookUp", id = "1", args = arrayOf("query" to "a"))),
            toolCallReply(call("lookUp", id = "2", args = arrayOf("query" to "b"))),
            textReply("here is what I found"),
            maxModelCalls = 3,
        ) { run, answer ->
            assertEquals("here is what I found", answer)
            assertEquals(3, run.client.requests.size)

            val wrapUp = run.client.requests[2]
            assertEquals(emptyList(), wrapUp.toolNames)
            assertContains((wrapUp.messages.last() as Message.User).text, "used up its tool budget")
            assertIs<Message.ToolResults>(wrapUp.messages[wrapUp.messages.lastIndex - 1])

            val queued = run.outbox.pending.map { it.output }.filterIsInstance<BotOutput.Text>()
            assertEquals("here is what I found", queued.single().text)
        }

    @Test
    fun `usage of every call is reported`() =
        run(
            com.helltar.vusan.llm.Reply(Message.Assistant(listOf(call("lookUp", args = arrayOf("query" to "a")))), com.helltar.vusan.llm.StopReason.TOOL_CALLS, TokenUsage(10, 2, 8, 1)),
            com.helltar.vusan.llm.Reply(Message.Assistant("ok"), com.helltar.vusan.llm.StopReason.END, TokenUsage(20, 3)),
        ) { run, _ ->
            assertEquals(listOf(TokenUsage(10, 2, 8, 1), TokenUsage(20, 3)), run.usages)
        }

    @Test
    fun `stored history is replayed with tool batches grouped the way the apis want them`() {
        val turns =
            listOf(
                ChatTurn(ChatRole.USER, "find two things"),
                ChatTurn(ChatRole.TOOL_CALL, """{"query":"a"}""", toolCallId = "1", toolName = "lookUp"),
                ChatTurn(ChatRole.TOOL_CALL, """{"query":"b"}""", toolCallId = "2", toolName = "lookUp"),
                ChatTurn(ChatRole.TOOL_RESULT, "found a", toolCallId = "1", toolName = "lookUp"),
                ChatTurn(ChatRole.TOOL_RESULT, "found b", toolCallId = "2", toolName = "lookUp", toolIsError = true),
                ChatTurn(ChatRole.ASSISTANT, "both found"),
            )

        val messages = turns.toMessages()

        assertEquals(4, messages.size)
        assertEquals(listOf("1", "2"), assertIs<Message.Assistant>(messages[1]).toolCalls.map { it.id })
        assertEquals("a", assertIs<Message.Assistant>(messages[1]).toolCalls.first().arguments.getValue("query").let { Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), it).trim('"') })
        assertEquals(listOf(false, true), assertIs<Message.ToolResults>(messages[2]).results.map { it.isError })
        assertEquals("both found", assertIs<Message.Assistant>(messages[3]).text)
    }

    @Test
    fun `history reaches the request between the system prompt and the current turn`() =
        run(
            textReply("ok"),
            history = listOf(ChatTurn(ChatRole.USER, "earlier question"), ChatTurn(ChatRole.ASSISTANT, "earlier answer")),
        ) { run, _ ->
            val messages = run.client.requests.single().messages

            assertIs<Message.System>(messages[0])
            assertEquals("earlier question", assertIs<Message.User>(messages[1]).text)
            assertEquals("earlier answer", assertIs<Message.Assistant>(messages[2]).text)
            assertEquals("the request", assertIs<Message.User>(messages[3]).text)
        }

    @Test
    fun `delivered nothing means no tool call and no text`() {
        assertTrue(Message.Assistant(emptyList()).deliveredNothing())
        assertTrue(Message.Assistant("   \n ").deliveredNothing())
        assertFalse(Message.Assistant("here you go").deliveredNothing())
        assertFalse(Message.Assistant(listOf(call("sendMessage"))).deliveredNothing())
        assertFalse(Message.Assistant(listOf(Part.Text(""), call("lookUp"))).deliveredNothing())
    }

    @Test
    fun `an empty reply with nothing queued is nudged to deliver once`() {
        val empty = Message.Assistant(emptyList())

        assertTrue(owesDelivery(empty, nudged = false, outboxHasOutput = false, silenceAllowed = false))
        assertFalse(owesDelivery(empty, nudged = true, outboxHasOutput = false, silenceAllowed = false))
        assertFalse(owesDelivery(empty, nudged = false, outboxHasOutput = true, silenceAllowed = false))
        assertFalse(owesDelivery(empty, nudged = false, outboxHasOutput = false, silenceAllowed = true))
    }

    @Test
    fun `only trailing empty assistants are dropped before the nudge re-request`() {
        val messages = mutableListOf<Message>(Message.User("hello"), Message.Assistant("earlier"), Message.User("ok then"), Message.Assistant(emptyList()))

        messages.dropTrailingEmptyAssistant()

        assertEquals(listOf(Message.User("hello"), Message.Assistant("earlier"), Message.User("ok then")), messages)

        val kept = mutableListOf<Message>(Message.User("ok"), Message.Assistant(""))
        kept.dropTrailingEmptyAssistant()
        assertEquals(2, kept.size, "a blank-text reply still serializes to a valid string content")
    }

    @Test
    fun `the last model call is reserved for the wrap-up`() {
        assertFalse(outOfModelCalls(callsMade = 1, maxModelCalls = 3))
        assertTrue(outOfModelCalls(callsMade = 2, maxModelCalls = 3))
        assertFalse(outOfModelCalls(callsMade = 50, maxModelCalls = 60))
        assertTrue(outOfModelCalls(callsMade = 59, maxModelCalls = 60))
    }

    @Test
    fun `a tool result within the cap is left alone and a long one is cut with a notice`() {
        val short = "x".repeat(100)
        assertEquals(short, short.boundedToolText(500))

        val long = "x".repeat(5_000)
        val bounded = long.boundedToolText(500)
        assertEquals(1_500, bounded.length)
        assertTrue(bounded.endsWith("[tool result truncated for the model context]"))
    }

    // the estimator reads bytes, so a token budget buys half the cyrillic characters it buys latin ones
    @Test
    fun `cyrillic costs the run budget more than latin of the same length`() {
        val latin = "a".repeat(3_000).boundedToolText(500)
        val cyrillic = "ж".repeat(3_000).boundedToolText(500)

        assertTrue(cyrillic.length < latin.length)
        assertTrue(estimateTokens(cyrillic) <= 500 + 20)
    }

    @Test
    fun `a cap too small for the truncation notice omits the result instead`() {
        assertTrue("x".repeat(400).boundedToolText(3).startsWith("[tool result omitted"))
    }

    @Test
    fun `a reasoning part of another provider is kept in the message`() {
        val message = Message.Assistant(listOf(Part.Reasoning(LlmProvider.ANTHROPIC, buildJsonObject { put("type", "thinking") }), Part.Text("ok")))

        assertEquals("ok", message.text)
        assertFalse(message.deliveredNothing())
    }

    @Test
    fun `stored tool arguments that do not parse replay as none`() {
        val turns = listOf(ChatTurn(ChatRole.TOOL_CALL, "not json", toolCallId = "1", toolName = "lookUp"), ChatTurn(ChatRole.TOOL_RESULT, "r", toolCallId = "1", toolName = "lookUp"))

        assertEquals(0, assertIs<Message.Assistant>(turns.toMessages()[0]).toolCalls.single().arguments.size)
    }

    @Test
    fun `garbled call message names the arguments the model has to send`() {
        assertEquals(
            "Tool `createPoll` was called with no arguments at all; it takes: question, options. Reissue it as a single, complete call with its arguments.",
            garbledCallMessage("createPoll", listOf("question", "options")),
        )
    }

    @Test
    fun `tool args are stored as the json the model sent`() {
        val json = """{"command":"printf \"sample\"","path":"C:\\tmp"}"""
        val parsed = Json.parseToJsonElement(json).jsonObject

        assertEquals(parsed, Json.parseToJsonElement(parsed.toString()).jsonObject)
    }
}
