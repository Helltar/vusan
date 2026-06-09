package com.helltar.vusan.agent.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatHistoryTest {

    @Test
    fun `short history is returned verbatim without a summary`() {
        val turns =
            listOf(
                ChatTurn(ChatRole.USER, "hi"),
                ChatTurn(ChatRole.ASSISTANT, "hello")
            )

        val result = summarizeForPrompt(turns)

        assertNull(result.summary)
        assertEquals(turns, result.turns)
    }

    @Test
    fun `long history condenses older turns and keeps a recap header`() {
        val result = summarizeForPrompt(historyWithToolPairAtNaiveSliceBoundary())

        val summary = result.summary
        assertTrue(summary != null, "expected a summary for a long history")
        assertTrue("Earlier conversation recap" in summary)
        assertTrue("- User: older-user-0" in summary)
        assertTrue("- Assistant: older-assistant-0" in summary)
    }

    @Test
    fun `recent slice never starts on an orphan tool turn`() {
        val history = historyWithToolPairAtNaiveSliceBoundary()

        val recent = summarizeForPrompt(history).turns

        // the naive last-12 slice would start mid-exchange on a TOOL_RESULT; the slice must extend
        // back to the owning USER turn so each tool-call/result pair stays anchored.
        assertEquals(ChatRole.USER, recent.first().role)
        assertEquals("boundary-user", recent.first().content)
        assertTrue(recent.none { it.role == ChatRole.TOOL_RESULT && it == recent.first() })
    }

    @Test
    fun `recent slice never starts on an orphan tool turn when no user anchor survives`() {
        // no USER turn in the window (a tool-heavy request overflowed the cap and lost its anchor):
        // the slice must not start on an orphan tool turn — they fold into the summary instead.
        val history =
            buildList {
                repeat(20) { i ->
                    add(ChatTurn(ChatRole.TOOL_CALL, "{}", toolCallId = "t$i", toolName = "search"))
                    add(ChatTurn(ChatRole.TOOL_RESULT, "result-$i", toolCallId = "t$i", toolName = "search"))
                }
                add(ChatTurn(ChatRole.ASSISTANT, "final answer"))
            }

        val result = summarizeForPrompt(history)

        val first = result.turns.firstOrNull()
        assertTrue(
            first == null || (first.role != ChatRole.TOOL_CALL && first.role != ChatRole.TOOL_RESULT),
            "recent slice must not start on an orphan tool turn, was ${first?.role}"
        )
        assertTrue(result.turns.none { it.role == ChatRole.TOOL_CALL || it.role == ChatRole.TOOL_RESULT })
    }

    // 16 turns where index (16 - PROMPT_RECENT_TURNS) lands on a TOOL_RESULT, so the slice has to
    // walk back past its TOOL_CALL to the preceding USER turn ("boundary-user").
    private fun historyWithToolPairAtNaiveSliceBoundary(): List<ChatTurn> =
        buildList {
            add(ChatTurn(ChatRole.USER, "older-user-0"))
            add(ChatTurn(ChatRole.ASSISTANT, "older-assistant-0"))
            add(ChatTurn(ChatRole.USER, "boundary-user"))
            add(ChatTurn(ChatRole.TOOL_CALL, "{}", toolCallId = "t1", toolName = "search"))
            add(ChatTurn(ChatRole.TOOL_RESULT, "result", toolCallId = "t1", toolName = "search"))
            repeat(11) { i ->
                val role = if (i % 2 == 0) ChatRole.ASSISTANT else ChatRole.USER
                add(ChatTurn(role, "pad-$i"))
            }
        }
}
