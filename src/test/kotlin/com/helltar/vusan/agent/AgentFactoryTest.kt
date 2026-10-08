package com.helltar.vusan.agent

import com.helltar.vusan.agent.conversation.ChatRole
import com.helltar.vusan.agent.conversation.ChatTurn
import com.helltar.vusan.agent.conversation.PromptConversation
import com.helltar.vusan.agent.conversation.toolCallArgsForStorage
import com.helltar.vusan.config.codexSessionLabel
import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.ScriptedLlmClient
import com.helltar.vusan.llm.TEST_MODEL
import com.helltar.vusan.llm.textReply
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.testScope
import com.helltar.vusan.tools.toolCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AgentFactoryTest {

    private fun firstRequest(history: List<ChatTurn> = emptyList(), summary: String? = null): ChatRequest =
        runBlocking {
            val client = ScriptedLlmClient(textReply("ok"))
            val factory = AgentFactory(client, TEST_MODEL, RequestOptions(promptCacheKey = "vusan"), maxModelCalls = 20)
            val preparation = factory.prepare(toolCatalog { }, "the request")

            factory.build(
                scope = testScope(),
                conversation = PromptConversation(summary = summary, turns = history),
                preparation = preparation,
                outbox = BotOutbox(),
                toolBudget = TurnToolBudget(factory.liveToolResultMaxTokens),
                toolEvents = {},
                tokenUsage = {},
            ).run("the request")

            client.requests.single()
        }

    @Test
    fun `history reaches the request between the system prompt and the current turn`() {
        val messages =
            firstRequest(history = listOf(ChatTurn(ChatRole.USER, "earlier question"), ChatTurn(ChatRole.ASSISTANT, "earlier answer"))).messages

        assertIs<Message.System>(messages[0])
        assertEquals("earlier question", assertIs<Message.User>(messages[1]).text)
        assertEquals("earlier answer", assertIs<Message.Assistant>(messages[2]).text)
        assertEquals("the request", assertIs<Message.User>(messages[3]).text)
    }

    @Test
    fun `the recap rides as a user message ahead of the history`() {
        val messages = firstRequest(history = listOf(ChatTurn(ChatRole.USER, "earlier question")), summary = "they asked about cats").messages

        assertEquals("<conversation_recap>\nthey asked about cats\n</conversation_recap>", assertIs<Message.User>(messages[1]).text)
        assertEquals("earlier question", assertIs<Message.User>(messages[2]).text)
    }

    @Test
    fun `each conversation caches under a key of its own`() {
        assertEquals("vusan-${testScope().toString().hashCode().toUInt().toString(16)}", firstRequest().options.promptCacheKey)
    }

    // the codex call lines carry the session the cache key folds into; a turn logging the same is what ties
    // a turn to its calls
    @Test
    fun `a turn is logged under the session its calls carry`() {
        val options = RequestOptions(promptCacheKey = "vusan")
        val factory = AgentFactory(ScriptedLlmClient(), TEST_MODEL, options, maxModelCalls = 20)
        val key = requireNotNull(options.forConversation(testScope().toString()).promptCacheKey)

        assertEquals(codexSessionLabel(key), factory.sessionLogLabel(testScope()))
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
        assertEquals("a", assertIs<Message.Assistant>(messages[1]).toolCalls.first().arguments.getValue("query").jsonPrimitive.content)
        assertEquals(listOf(false, true), assertIs<Message.ToolResults>(messages[2]).results.map { it.isError })
        assertEquals("both found", assertIs<Message.Assistant>(messages[3]).text)
    }

    @Test
    fun `stored tool arguments replay as the json the model sent`() {
        val sent =
            buildJsonObject {
                put("command", """printf "sample"""")
                put("path", """C:\tmp""")
            }

        val turns =
            listOf(
                ChatTurn(ChatRole.TOOL_CALL, toolCallArgsForStorage(sent.toString()), toolCallId = "1", toolName = "runCommand"),
                ChatTurn(ChatRole.TOOL_RESULT, "done", toolCallId = "1", toolName = "runCommand"),
            )

        assertEquals(sent, assertIs<Message.Assistant>(turns.toMessages()[0]).toolCalls.single().arguments)
    }

    @Test
    fun `stored tool arguments that do not parse replay as none`() {
        val turns = listOf(ChatTurn(ChatRole.TOOL_CALL, "not json", toolCallId = "1", toolName = "lookUp"), ChatTurn(ChatRole.TOOL_RESULT, "r", toolCallId = "1", toolName = "lookUp"))

        assertEquals(0, assertIs<Message.Assistant>(turns.toMessages()[0]).toolCalls.single().arguments.size)
    }
}
