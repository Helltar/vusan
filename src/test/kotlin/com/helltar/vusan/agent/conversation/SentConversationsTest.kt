package com.helltar.vusan.agent.conversation

import com.helltar.vusan.llm.Message
import com.helltar.vusan.request.testScope
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class SentConversationsTest {

    private val scope = testScope(userId = 42, chatId = 42)

    private fun sent(system: String = "the system prompt", history: String = "an earlier answer") =
        SentConversations.Sent(
            messages = listOf(Message.System(system), Message.User("an earlier request"), Message.Assistant(history)),
            storedInteractionId = "stored-1",
            nextCall = 3,
            memoryFingerprint = 11,
        )

    private fun SentConversations.continuation(
        newestStoredInteractionId: String? = "stored-1",
        systemPrompt: String = "the system prompt",
        memoryFingerprint: Int = 11,
        storedHistoryTokens: Int = 20,
        historyBudget: Int = 100_000,
    ) = continuation(scope, systemPrompt, newestStoredInteractionId, memoryFingerprint, storedHistoryTokens, historyBudget)

    @Test
    fun `a request that still stands on the stored history is continued`() {
        val kept = SentConversations()
        val request = sent()
        kept.remember(scope, request)

        assertSame(request, kept.continuation())
        assertSame(request, kept.continuation(), "continuing a request does not use it up")
    }

    @Test
    fun `nothing is continued where nothing was kept`() {
        assertNull(SentConversations().continuation())
        assertNull(SentConversations().apply { remember(testScope(userId = 7, chatId = 7), sent()) }.continuation())
    }

    // a clear, a turn stored without a request to continue, or a recap of everything: the history moved on
    @Test
    fun `a request the stored history has moved past is dropped`() {
        val kept = SentConversations()
        kept.remember(scope, sent())

        assertNull(kept.continuation(newestStoredInteractionId = "stored-2"))
        assertNull(kept.continuation(), "dropped, not only passed over")
    }

    @Test
    fun `a request sent under another system prompt is dropped`() {
        val kept = SentConversations()
        kept.remember(scope, sent())

        assertNull(kept.continuation(systemPrompt = "a new personality"))
    }

    // every earlier message carries its own turn's memory, so an entry removed since would live on in them
    @Test
    fun `a request sent with other memory is dropped`() {
        val kept = SentConversations()
        kept.remember(scope, sent())

        assertNull(kept.continuation(memoryFingerprint = 12))
    }

    @Test
    fun `a request over the history budget is dropped`() {
        val kept = SentConversations()
        kept.remember(scope, sent(history = "x".repeat(3_000)))

        assertNull(kept.continuation(storedHistoryTokens = 1_000, historyBudget = 900))
    }

    // the stored history is the lean one: a continued request may carry more, but not without end
    @Test
    fun `a request grown far past the stored history is dropped`() {
        val kept = SentConversations()
        kept.remember(scope, sent(history = "x".repeat(60_000)))

        assertNull(kept.continuation(storedHistoryTokens = 2_000))
    }

    @Test
    fun `only the most recent conversations are kept`() {
        val kept = SentConversations(capacity = 1)
        kept.remember(scope, sent())
        kept.remember(testScope(userId = 7, chatId = 7), sent())

        assertNull(kept.continuation())
    }
}
