package com.helltar.vusan.agent

import com.helltar.vusan.agent.conversation.PromptConversation
import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.Reply
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.ScriptedLlmClient
import com.helltar.vusan.llm.StopReason
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

private class DrawTools : ToolSet {

    @Tool("Draws a picture.")
    fun draw(@Arg("What.") subject: String): String = "drew $subject"
}

class AgentTurnTest {

    private fun call(name: String, id: String = "c-$name", vararg args: Pair<String, String>) =
        Part.ToolCall(id, name, buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

    private class Run(
        val client: ScriptedLlmClient,
        val outbox: BotOutbox = BotOutbox(),
        val events: MutableList<ToolEvent> = mutableListOf(),
        val usages: MutableList<TokenUsage> = mutableListOf(),
    )

    private fun run(
        vararg replies: Reply,
        maxModelCalls: Int = 20,
        mayStaySilent: Boolean = false,
        catalog: (BotOutbox) -> ToolCatalog = { outbox -> toolCatalog { tools(ProbeTools(outbox)); tools(ToolGroup.IMAGE_GENERATION, DrawTools()) } },
        // passed in by a test that has to look at the run after it throws
        run: Run = Run(ScriptedLlmClient(*replies)),
        block: (Run, String) -> Unit,
    ) = runBlocking {
        val factory = AgentFactory(run.client, TEST_MODEL, RequestOptions(promptCacheKey = "vusan"), maxModelCalls = maxModelCalls)
        val tools = catalog(run.outbox)
        val preparation = factory.prepare(tools, "the request")
        val budget = TurnToolBudget(factory.liveToolResultMaxTokens)

        val answer =
            factory.build(
                scope = testScope(),
                conversation = PromptConversation(summary = null, turns = emptyList()),
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
            assertEquals(run.client.requests[1].toolNames, wrapUp.toolNames, "the tools stay defined for the calls the turn replays")
            assertFalse(wrapUp.mayCallTools)
            assertContains((wrapUp.messages.last() as Message.User).text, "used up its tool budget")
            assertIs<Message.ToolResults>(wrapUp.messages[wrapUp.messages.lastIndex - 1])

            val queued = run.outbox.pending.map { it.output }.filterIsInstance<BotOutput.Text>()
            assertEquals("here is what I found", queued.single().text)
        }

    @Test
    fun `a reply cut at the output ceiling runs none of its calls and tells the model why`() =
        run(
            Reply(Message.Assistant(listOf(call("sendMessage", args = arrayOf("text" to "half an ans")))), StopReason.MAX_TOKENS),
            textReply("the whole answer"),
        ) { run, answer ->
            assertEquals("the whole answer", answer)
            assertFalse(run.outbox.hasQueuedOutput, "the cut call never ran")
            assertTrue(run.events.isEmpty(), "a call that never ran leaves no history")

            val result = assertIs<Message.ToolResults>(run.client.requests[1].messages.last()).results.single()
            assertTrue(result.isError)
            assertContains(result.output, "output limit")
        }

    @Test
    fun `a declined reply ends the turn without running its calls`() {
        val declined = Run(ScriptedLlmClient(Reply(Message.Assistant(listOf(call("sendMessage", args = arrayOf("text" to "partial")))), StopReason.REFUSAL, refusal = "cyber")))

        val refusal = assertFailsWith<ModelRefusal> { run(run = declined) { _, _ -> } }

        assertEquals("cyber", refusal.reason)
        assertEquals(1, declined.client.requests.size, "a refusal is not nudged: the same prompt is declined again")
        assertFalse(declined.outbox.hasQueuedOutput)
        assertTrue(declined.events.isEmpty())
    }

    @Test
    fun `usage of every call is reported`() =
        run(
            Reply(Message.Assistant(listOf(call("lookUp", args = arrayOf("query" to "a")))), StopReason.TOOL_CALLS, TokenUsage(10, 2, 8, 1)),
            Reply(Message.Assistant("ok"), StopReason.END, TokenUsage(20, 3)),
        ) { run, _ ->
            assertEquals(listOf(TokenUsage(10, 2, 8, 1), TokenUsage(20, 3)), run.usages)
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

    // anthropic refuses an assistant turn left empty, which is what a blank text block becomes there
    @Test
    fun `a trailing reply that delivered nothing is dropped before the nudge, whatever it holds`() {
        val thinking = Part.Reasoning("https://api.anthropic.com/v1/messages", buildJsonObject { put("type", "thinking") })

        val messages =
            mutableListOf<Message>(
                Message.User("hello"),
                Message.Assistant("earlier"),
                Message.User("ok then"),
                Message.Assistant(emptyList()),
                Message.Assistant(""),
                Message.Assistant(listOf(thinking, Part.Text("  "))),
            )

        messages.dropTrailingSilentAssistant()

        assertEquals(listOf(Message.User("hello"), Message.Assistant("earlier"), Message.User("ok then")), messages)
    }

    // the nudge is a model call, and the last one is the wrap-up's: past it the turn would make one more
    @Test
    fun `an empty reply on the last call before the wrap-up goes to the wrap-up instead of a nudge`() =
        run(toolCallReply(call("lookUp", args = arrayOf("query" to "a"))), textReply(""), textReply("what I found"), maxModelCalls = 3) { run, answer ->
            assertEquals("what I found", answer)
            assertEquals(3, run.client.requests.size)

            val last = run.client.requests[2]
            assertContains((last.messages.last() as Message.User).text, "used up its tool budget")
            assertFalse(last.messages.any { it is Message.Assistant && it.deliveredNothing() }, "the empty reply is not replayed")
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
        val message = Message.Assistant(listOf(Part.Reasoning("https://api.anthropic.com/v1/messages", buildJsonObject { put("type", "thinking") }), Part.Text("ok")))

        assertEquals("ok", message.text)
        assertFalse(message.deliveredNothing())
    }

    @Test
    fun `garbled call message names the arguments the model has to send`() {
        assertEquals(
            "Tool `createPoll` was called with no arguments at all; it takes: question, options. Reissue it as a single, complete call with its arguments.",
            garbledCallMessage("createPoll", listOf("question", "options")),
        )
    }
}
