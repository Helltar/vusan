package com.helltar.vusan.agent.conversation

import com.helltar.vusan.llm.FakeLlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.LlmProvider
import com.helltar.vusan.llm.Message
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ConversationCompactorTest {

    @Test
    fun `compactor merges previous recap and events into a persisted checkpoint`() = runBlocking {
        val executor = FakeLlmClient("- User prefers tea\n- Open thread: movie night")
        val model = LlmModel(LlmProvider.OPENAI, "test", contextWindowTokens = 16_384)
        val compactor = LlmConversationCompactor(executor, model)
        val interaction =
            ConversationInteraction(
                id = "i-1",
                lastMessageId = 7,
                createdAt = Instant.EPOCH,
                turns =
                    listOf(
                        ChatTurn(ChatRole.USER, "I prefer tea"),
                        ChatTurn(ChatRole.ASSISTANT, "got it"),
                    ),
            )

        val result = compactor.compact("The user likes warm drinks.", listOf(interaction))

        assertEquals("- User prefers tea\n- Open thread: movie night", result?.summary)
        assertEquals(7, result?.throughMessageId)
        assertContains((checkNotNull(executor.lastRequest).messages.last() as Message.User).text, "<previous_recap>")
        assertContains((checkNotNull(executor.lastRequest).messages.last() as Message.User).text, "<conversation_events>")
    }

    // a stored user entry is context first and request last, and the context alone can outrun the cap.
    @Test
    fun `a long reply context does not push the request out of the compaction source`() = runBlocking {
        val executor = FakeLlmClient("- Booking a flight")
        val model = LlmModel(LlmProvider.OPENAI, "test", contextWindowTokens = 16_384)
        val compactor = LlmConversationCompactor(executor, model)
        val storedEntry =
            buildString {
                appendLine("<reply_context>")
                appendLine("- author: olena")
                appendLine("- type: text")
                appendLine("<text_caption>")
                appendLine("the itinerary as she wrote it ".repeat(20))
                appendLine("</text_caption>")
                appendLine("</reply_context>")
                appendLine()
                appendLine("<quoted_fragment>")
                appendLine("the paragraph she highlighted ".repeat(35))
                appendLine("</quoted_fragment>")
                appendLine()
                append("<user_message>\nbook the Tuesday flight instead\n</user_message>")
            }
        val interaction =
            ConversationInteraction(
                id = "i-2",
                lastMessageId = 11,
                createdAt = Instant.EPOCH,
                turns = listOf(ChatTurn(ChatRole.USER, storedEntry), ChatTurn(ChatRole.ASSISTANT, "done")),
            )

        compactor.compact(null, listOf(interaction))

        val source = (checkNotNull(executor.lastRequest).messages.last() as Message.User).text

        assertContains(source, "book the Tuesday flight instead")
        assertContains(source, "- author: olena", message = "the context in front of the request is kept too")
    }
}
