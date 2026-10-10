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
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.testScope
import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolCatalog
import com.helltar.vusan.tools.ToolGroup
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.suspendToolGuard
import com.helltar.vusan.tools.keepOnShelf
import com.helltar.vusan.tools.toolCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// where calls that run side by side meet: each waits a moment for the other, and says whether it came
private class Rendezvous(private val expected: Int) {

    private val waiting = AtomicInteger()
    private val met = CompletableDeferred<Unit>()

    suspend fun meet(): Boolean {
        if (waiting.incrementAndGet() == expected) met.complete(Unit)

        return try {
            withTimeoutOrNull(300.milliseconds) { met.await() } != null
        } finally {
            waiting.decrementAndGet()
        }
    }
}

private class ProbeTools(private val outbox: BotOutbox, private val rendezvous: Rendezvous = Rendezvous(2)) : ToolSet {

    @Tool("Sends text to the user.")
    fun sendMessage(@Arg("The text.") text: String): String {
        outbox.enqueueText(text)
        return "Delivered."
    }

    @Tool("Says what comes next.")
    fun announcePlan(@Arg("The plan.") text: String): String {
        outbox.recordDelivered(text)
        return "Sent."
    }

    @Tool("Looks something up.")
    fun lookUp(@Arg("What.") query: String): String = "found: $query"

    @Tool("Looks something up without touching anything.", readOnly = true)
    suspend fun lookUpTogether(@Arg("What.") query: String): String = if (rendezvous.meet()) "together: $query" else "alone: $query"

    @Tool("Always fails.")
    suspend fun explode(@Arg("Why.") reason: String): String = suspendToolGuard { error("boom: $reason") }
}

// one call makes a file and keeps it, a later one takes it by its label
private class MakeAndTakeTools : ToolSet {

    @Tool("Draws a picture and keeps it.")
    suspend fun sketch(@Arg("What.") subject: String): String {
        keepOnShelf("$subject.png", byteArrayOf(1, 2, 3))
        return "sketched $subject"
    }

    @Tool("Looks at a file.")
    suspend fun inspect(@Arg("Which.") file: AttachedFile): String = "saw ${file.name}, ${file.loadBytes().size} bytes"

    @Tool("Repeats text.")
    fun echo(@Arg("What.", takesReference = true) text: String): String = "echo: $text"
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
        catalog: (BotOutbox) -> ToolCatalog = { outbox -> toolCatalog { tools(ProbeTools(outbox)); tools(ToolGroup.GIFS, DrawTools()) } },
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
                shelf = TurnShelf(),
            ).run("the request")

        block(run, answer)
    }

    private val ChatRequest.toolNames: List<String>
        get() = tools.map { it.name }

    @Test
    fun `a file one call made is what a later call takes by its label`() =
        run(
            toolCallReply(call("sketch", args = arrayOf("subject" to "cat"))),
            toolCallReply(call("inspect", args = arrayOf("file" to "#1/1")), call("echo", args = arrayOf("text" to "#1"))),
            textReply("done"),
            catalog = { toolCatalog { tools(MakeAndTakeTools()) } },
        ) { run, _ ->
            val made = assertIs<Message.ToolResults>(run.client.requests[1].messages.last()).results.single().output
            val taken = assertIs<Message.ToolResults>(run.client.requests[2].messages.last()).results.map { it.output }

            assertEquals("[#1] sketched cat\n[#1/1] cat.png — image, 3 B", made)
            assertEquals(listOf("[#2] saw cat.png, 3 bytes", "[#3] echo: sketched cat"), taken)
            assertEquals("sketched cat", run.events.first().output, "history keeps the result without its label")
        }

    @Test
    fun `a reference to nothing is answered with what went wrong`() =
        run(
            toolCallReply(call("inspect", args = arrayOf("file" to "#7/1"))),
            textReply("done"),
            catalog = { toolCatalog { tools(MakeAndTakeTools()) } },
        ) { run, _ ->
            val result = assertIs<Message.ToolResults>(run.client.requests[1].messages.last()).results.single()

            assertTrue(result.isError)
            assertContains(result.output, "rejected its arguments")
        }

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
            assertEquals("[#1] found: cats", results.results.single().output)
            assertEquals("c-lookUp", results.results.single().callId)
            assertFalse(results.results.single().isError)

            val event = run.events.single()
            assertEquals("lookUp", event.toolName)
            assertEquals("""{"query":"cats"}""", event.args)
        }

    // a model stuck on one call reads the same answer until the budget ends the turn
    @Test
    fun `the third call with the same arguments is told it is repeating itself`() =
        run(
            toolCallReply(call("lookUp", id = "c1", args = arrayOf("query" to "cats"))),
            toolCallReply(call("lookUp", id = "c2", args = arrayOf("query" to "cats"))),
            toolCallReply(call("lookUp", id = "c3", args = arrayOf("query" to "cats"))),
            textReply("done"),
        ) { run, _ ->
            val outputs = run.client.requests.drop(1).map { assertIs<Message.ToolResults>(it.messages.last()).results.single().output }

            assertEquals("[#2] found: cats", outputs[1])
            assertTrue(outputs[2].startsWith("[#3] found: cats\n\nThis is call 3 of `lookUp`"), outputs[2])
        }

    @Test
    fun `read-only calls standing together run side by side, and the results keep the batch order`() =
        run(toolCallReply(call("lookUpTogether", "a", "query" to "a"), call("lookUpTogether", "b", "query" to "b")), textReply("ok")) { run, _ ->
            val results = assertIs<Message.ToolResults>(run.client.requests[1].messages.last()).results

            assertEquals(listOf("[#1] together: a", "[#2] together: b"), results.map { it.output })
            assertEquals(listOf("a", "b"), results.map { it.callId })
            assertEquals(listOf("a", "b"), run.events.map { it.toolCallId })
        }

    // a call that may change something keeps its place in the batch, so the reads on either side of it
    // run one at a time
    @Test
    fun `a call that is not read-only keeps the batch in order`() =
        run(
            toolCallReply(call("lookUpTogether", "a", "query" to "a"), call("lookUp", "x", "query" to "x"), call("lookUpTogether", "b", "query" to "b")),
            textReply("ok"),
        ) { run, _ ->
            val results = assertIs<Message.ToolResults>(run.client.requests[1].messages.last()).results

            assertEquals(listOf("[#1] alone: a", "[#2] found: x", "[#3] alone: b"), results.map { it.output })
        }

    // the announcement reached the chat, so a turn that stops there leaves the user waiting on a promise
    @Test
    fun `a turn that announced its plan and stopped is sent back to the work once`() =
        run(toolCallReply(call("announcePlan", args = arrayOf("text" to "building it"))), textReply("ok"), textReply("done")) { run, answer ->
            assertEquals("done", answer)
            assertEquals(3, run.client.requests.size)
            assertEquals(PROMISE_NUDGE, (run.client.requests[2].messages.last() as Message.User).text)
        }

    @Test
    fun `a turn that announced its plan and then worked is not nudged`() =
        run(
            toolCallReply(call("announcePlan", args = arrayOf("text" to "building it"))),
            toolCallReply(call("sendMessage", args = arrayOf("text" to "built"))),
            textReply(""),
        ) { run, _ ->
            assertEquals(3, run.client.requests.size)
            assertFalse(run.client.requests.any { request -> request.messages.any { it is Message.User && it.text == PROMISE_NUDGE } })
        }

    // three calls with long arguments outgrow a 16k window: before the request that follows, the oldest
    // batches are folded away, the latest kept whole, so the turn goes on instead of overflowing
    @Test
    fun `a turn whose own pile outgrows the window has its oldest results folded before the next request`() {
        val long = "y".repeat(20_000)

        run(
            toolCallReply(call("lookUp", "c1", "query" to long)),
            toolCallReply(call("lookUp", "c2", "query" to long)),
            toolCallReply(call("lookUp", "c3", "query" to long)),
            textReply("done"),
        ) { run, answer ->
            assertEquals("done", answer)

            val last = run.client.requests.last().messages
            val results = last.filterIsInstance<Message.ToolResults>().map { it.results.single() }

            assertEquals(listOf("c1", "c2", "c3"), results.map { it.callId })
            // folded under the labels the shelf still answers to
            assertEquals("[#1] $FOLDED_RESULT", results[0].output)
            assertEquals("[#2] $FOLDED_RESULT", results[1].output)
            // the run's own budget already cut the latest result down; folding never touches it
            assertFalse(results[2].output.endsWith(FOLDED_RESULT), "the latest batch is never folded")

            val calls = last.filterIsInstance<Message.Assistant>().flatMap { it.toolCalls }
            assertTrue("_dropped" in calls[0].arguments)
            assertTrue("query" in calls[2].arguments)
        }
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
        run(toolCallReply(call("loadTools", args = arrayOf("groups" to "gifs"))), toolCallReply(call("draw", args = arrayOf("subject" to "a cat"))), textReply("done")) { run, _ ->
            assertFalse("draw" in run.client.requests[0].toolNames)
            assertTrue("draw" in run.client.requests[1].toolNames)
            assertEquals("[#2] drew a cat", assertIs<Message.ToolResults>(run.client.requests[2].messages.last()).results.single().output)
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
