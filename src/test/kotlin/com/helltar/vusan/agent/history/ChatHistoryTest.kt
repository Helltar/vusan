package com.helltar.vusan.agent.history

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatHistoryTest {

    @Test
    fun `short history is returned verbatim within the token budget`() {
        val interactions = listOf(exchange(1, "hi", "hello"), exchange(2, "still there", "yep"))

        val result = planHistoryForPrompt(snapshot(interactions), tokenBudget = 10_000, maxRecentInteractions = 12)

        assertEquals(interactions.flatMap { it.turns }, result.history.turns)
        assertTrue(result.compactablePrefix.isEmpty())
        assertEquals(2, result.includedInteractions)
    }

    @Test
    fun `recent limit compacts a whole interaction prefix`() {
        val interactions = (1L..5L).map { exchange(it, "user-$it", "assistant-$it") }

        val result = planHistoryForPrompt(snapshot(interactions), tokenBudget = 10_000, maxRecentInteractions = 3)

        assertEquals(listOf("i-1", "i-2", "i-3"), result.compactablePrefix.map { it.id })
        assertEquals(listOf("user-3", "assistant-3", "user-4", "assistant-4", "user-5", "assistant-5"), result.history.turns.map { it.content })
    }

    @Test
    fun `only the newest two interactions replay raw tool events`() {
        val interactions = (1L..3L).map { toolExchange(it) }

        val result = planHistoryForPrompt(snapshot(interactions), tokenBudget = 10_000, maxRecentInteractions = 12)

        assertEquals(2, result.exactToolInteractions)
        assertEquals(2, result.history.turns.count { it.role == ChatRole.TOOL_CALL })
        assertEquals(2, result.history.turns.count { it.role == ChatRole.TOOL_RESULT })
        assertTrue(result.compactablePrefix.isEmpty())
    }

    @Test
    fun `token budget keeps a newest complete interaction and compacts the older prefix`() {
        val interactions = (1L..3L).map { exchange(it, "u".repeat(120), "a".repeat(120)) }
        val latestTokens = estimateHistoryTokens(interactions.last().turns)

        val result =
            planHistoryForPrompt(
                snapshot(interactions),
                tokenBudget = latestTokens + 1,
                maxRecentInteractions = 12
            )

        assertEquals(listOf("i-1", "i-2"), result.compactablePrefix.map { it.id })
        assertEquals(interactions.last().turns, result.history.turns)
    }

    @Test
    fun `broken legacy tool fragments are not replayed`() {
        val interaction =
            ChatInteraction(
                id = "legacy",
                lastMessageId = 3,
                createdAt = Instant.EPOCH,
                turns =
                    listOf(
                        ChatTurn(ChatRole.USER, "hello"),
                        ChatTurn(ChatRole.TOOL_RESULT, "orphan", "call", "search"),
                        ChatTurn(ChatRole.ASSISTANT, "answer")
                    )
            )

        val result = planHistoryForPrompt(snapshot(listOf(interaction)), 10_000, 12)

        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), result.history.turns.map { it.role })
    }

    private fun exchange(id: Long, user: String, assistant: String): ChatInteraction =
        ChatInteraction(
            id = "i-$id",
            lastMessageId = id,
            createdAt = Instant.EPOCH,
            turns = listOf(ChatTurn(ChatRole.USER, user), ChatTurn(ChatRole.ASSISTANT, assistant))
        )

    private fun toolExchange(id: Long): ChatInteraction =
        ChatInteraction(
            id = "i-$id",
            lastMessageId = id,
            createdAt = Instant.EPOCH,
            turns =
                listOf(
                    ChatTurn(ChatRole.USER, "user-$id"),
                    ChatTurn(ChatRole.TOOL_CALL, "{}", "call-$id", "search"),
                    ChatTurn(ChatRole.TOOL_RESULT, "result-$id", "call-$id", "search"),
                    ChatTurn(ChatRole.ASSISTANT, "assistant-$id")
                )
        )

    private fun snapshot(interactions: List<ChatInteraction>): ChatHistorySnapshot =
        ChatHistorySnapshot(
            summary = null,
            summarizedThroughMessageId = 0,
            interactions = interactions,
            stats =
                ChatHistoryStats(
                    storedInteractions = interactions.size,
                    storedMessages = interactions.sumOf { it.turns.size },
                    storedChars = interactions.sumOf { interaction -> interaction.turns.sumOf { it.content.length.toLong() } },
                    unsummarizedInteractions = interactions.size,
                    unsummarizedMessages = interactions.sumOf { it.turns.size }
                )
        )
}
