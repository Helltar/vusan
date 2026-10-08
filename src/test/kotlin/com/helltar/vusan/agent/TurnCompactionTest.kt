package com.helltar.vusan.agent

import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TurnCompactionTest {

    private fun call(id: String, query: String) = Part.ToolCall(id, "lookUp", buildJsonObject { put("query", query) })

    private fun batch(id: String, query: String, output: String) =
        listOf(Message.Assistant(listOf(call(id, query))), Message.ToolResults(listOf(ToolResult(id, "lookUp", output))))

    private val long = "x".repeat(3_000)

    // the stored history and the request, then three rounds of the turn's own tool calls
    private val turn =
        listOf(Message.System("rules"), Message.User("stored question"), Message.Assistant("stored answer"), Message.User("the request")) +
                batch("c1", long, long) + batch("c2", "short", long) + batch("c3", long, long)

    private val turnStart = 3

    @Test
    fun `a request that fits is left alone`() {
        assertNull(turn.foldedToFit(ceilingTokens = 100_000, turnStart))
    }

    @Test
    fun `the oldest results are folded first, with the long arguments they answered, and the latest batch never`() {
        val tokens = turn.sumOf(::estimateTokens)
        val folded = turn.foldedToFit(ceilingTokens = tokens - 1_500, turnStart) ?: error("nothing folded")

        val firstResults = assertIs<Message.ToolResults>(folded[5])
        assertEquals(FOLDED_RESULT, firstResults.results.single().output)
        assertEquals("c1", firstResults.results.single().callId, "the call stays answered under its id")

        val firstCall = assertIs<Message.Assistant>(folded[4]).toolCalls.single()
        assertTrue("_dropped" in firstCall.arguments, "arguments of 3000 chars are dropped with the results they led to")
        assertEquals("c1", firstCall.id)

        assertSame(turn[6], folded[6], "the second batch still fits and is kept whole")
        assertSame(turn[8], folded[8], "the latest batch is never touched")
        assertSame(turn[9], folded[9])
        assertTrue(folded.sumOf(::estimateTokens) <= tokens - 1_500)
    }

    @Test
    fun `short arguments are kept when their results are folded, and nothing before the turn is touched`() {
        val folded = turn.foldedToFit(ceilingTokens = 300, turnStart) ?: error("nothing folded")

        (0 until turnStart).forEach { assertSame(turn[it], folded[it]) }
        assertSame(turn[3], folded[3], "the request itself stays")

        val secondCall = assertIs<Message.Assistant>(folded[6]).toolCalls.single()
        assertEquals("short", secondCall.arguments.getValue("query").jsonPrimitive.content)
        assertEquals(FOLDED_RESULT, assertIs<Message.ToolResults>(folded[7]).results.single().output)

        // the latest batch alone is over the ceiling here, and that is as far as folding goes
        assertEquals(long, assertIs<Message.ToolResults>(folded[9]).results.single().output)
    }

    @Test
    fun `a batch already folded is not counted twice`() {
        val once = turn.foldedToFit(ceilingTokens = 300, turnStart) ?: error("nothing folded")
        val twice = once.foldedToFit(ceilingTokens = 300, turnStart) ?: error("still over the ceiling")

        assertEquals(once, twice)
    }
}
