package com.helltar.vusan.agent

import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.ScriptedLlmClient
import com.helltar.vusan.llm.textReply
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class CompleteWithinTest {

    private val model = LlmModel("test", contextWindowTokens = 16_384)
    private val request = ChatRequest(listOf(Message.System("summarize"), Message.User("the day")))

    @Test
    fun `an answer within the limit is kept as it came, in one call`() = runBlocking {
        val client = ScriptedLlmClient(textReply("a".repeat(10)))

        val reply = client.completeWithin(model, request, askedChars = 8, maxChars = 10, what = "test")

        assertEquals("a".repeat(10), reply.message.text)
        assertEquals(1, client.requests.size)
    }

    // a cut drops whatever was written last, so the model is asked to write it again instead
    @Test
    fun `an answer over the limit is written again shorter rather than cut`() = runBlocking {
        val client = ScriptedLlmClient(textReply("b".repeat(15)), textReply("short"))

        val reply = client.completeWithin(model, request, askedChars = 8, maxChars = 10, what = "test")

        assertEquals("short", reply.message.text)
        val rewrite = client.requests[1].messages
        assertEquals(request.messages, rewrite.take(2))
        assertEquals("b".repeat(15), assertIs<Message.Assistant>(rewrite[2]).text)
        val ask = assertIs<Message.User>(rewrite[3]).text
        assertContains(ask, "15 characters")
        assertContains(ask, "at most 8 characters")
    }

    @Test
    fun `a rewrite still over the limit is cut, and only then`() = runBlocking {
        val client = ScriptedLlmClient(textReply("c".repeat(15)), textReply("d".repeat(12)))

        val reply = client.completeWithin(model, request, askedChars = 8, maxChars = 10, what = "test")

        assertEquals(2, client.requests.size)
        assertEquals(10, reply.message.text.length)
        assertContains(reply.message.text, "ddd")
    }

    @Test
    fun `an empty rewrite falls back to the first answer, cut`() = runBlocking {
        val client = ScriptedLlmClient(textReply("e".repeat(15)), textReply(" "))

        val reply = client.completeWithin(model, request, askedChars = 8, maxChars = 10, what = "test")

        assertEquals(10, reply.message.text.length)
        assertContains(reply.message.text, "eee")
    }
}
