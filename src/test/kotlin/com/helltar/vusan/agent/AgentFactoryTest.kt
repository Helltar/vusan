package com.helltar.vusan.agent

import com.helltar.vusan.agent.conversation.ChatRole
import com.helltar.vusan.agent.conversation.ChatTurn
import com.helltar.vusan.agent.conversation.PromptConversation
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AgentFactoryTest {

    private fun firstRequest(history: List<ChatTurn> = emptyList(), summary: String? = null, stableTurns: Int = 0): ChatRequest =
        runBlocking {
            val client = ScriptedLlmClient(textReply("ok"))
            val factory = AgentFactory(client, TEST_MODEL, RequestOptions(promptCacheKey = "vusan"), maxModelCalls = 20)
            val preparation = factory.prepare(toolCatalog { }, "the request")

            factory.build(
                scope = testScope(),
                conversation = PromptConversation(summary = summary, turns = history, stableTurns = stableTurns),
                preparation = preparation,
                outbox = BotOutbox(),
                toolBudget = TurnToolBudget(factory.liveToolResultMaxTokens),
                toolEvents = {},
                tokenUsage = {},
                shelf = TurnShelf(),
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

    // the next turn replays the part replayed as text the same way for sure, and all of it when the newest
    // interactions replay the same way too, so the end of each is where it can read this turn's cache
    @Test
    fun `the request marks where the replayed history ends`() {
        val history =
            listOf(
                ChatTurn(ChatRole.USER, "first question"),
                ChatTurn(ChatRole.ASSISTANT, "first answer"),
                ChatTurn(ChatRole.USER, "second question"),
                ChatTurn(ChatRole.ASSISTANT, "second answer"),
            )

        assertEquals(listOf(3, 5), firstRequest(history = history, stableTurns = 2).stablePrefixes)
        assertEquals(listOf(4, 6), firstRequest(history = history, summary = "a recap", stableTurns = 2).stablePrefixes)
        assertEquals(listOf(5), firstRequest(history = history, stableTurns = 4).stablePrefixes)
        assertEquals(listOf(2), firstRequest(summary = "a recap").stablePrefixes)
        assertEquals(emptyList(), firstRequest().stablePrefixes, "the system prompt alone is the client's to mark")
    }

    @Test
    fun `each conversation caches under a key of its own`() {
        assertEquals("vusan-${testScope().toString().hashCode().toUInt().toString(16)}", firstRequest().options.promptCacheKey)
    }

    // a provider's call lines name the conversation by its cache key; a turn logging the same is what ties
    // a turn to its calls, and a provider without a key names none
    @Test
    fun `a turn is logged under the cache key its calls carry`() {
        val options = RequestOptions(promptCacheKey = "vusan")
        val factory = AgentFactory(ScriptedLlmClient(), TEST_MODEL, options, maxModelCalls = 20)

        assertEquals(options.forConversation(testScope().toString()).promptCacheKey, factory.conversationCacheKey(testScope()))
        assertNull(AgentFactory(ScriptedLlmClient(), TEST_MODEL, RequestOptions(), maxModelCalls = 20).conversationCacheKey(testScope()))
    }
}
