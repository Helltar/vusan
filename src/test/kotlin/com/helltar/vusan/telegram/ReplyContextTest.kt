package com.helltar.vusan.telegram

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyContextTest {

    @Test
    fun `formatAgentInput includes replied text before current user message`() {
        val prompt =
            formatAgentInput(
                currentMessageText = "summarize this article",
                repliedMessage = RepliedMessageSummary(type = "text", textOrCaption = "https://example.com/article/4034")
            )

        assertTrue(prompt.contains("<reply_context>"))
        assertTrue(prompt.contains("- type: text"))
        assertTrue(prompt.contains("https://example.com/article/4034"))
        assertTrue(prompt.contains("</reply_context>"))
        assertTrue(prompt.contains("<user_message>"))
        assertTrue(prompt.contains("summarize this article"))
        assertTrue(prompt.contains("</user_message>"))
    }

    @Test
    fun `formatAgentInput handles media without caption`() {
        val prompt =
            formatAgentInput(
                currentMessageText = "what's in the photo?",
                repliedMessage = RepliedMessageSummary(
                    type = "photo",
                    textOrCaption = null,
                    metadata = listOf("file_id: abc123", "width: 1280", "height: 720")
                )
            )

        assertTrue(prompt.contains("- type: photo"))
        assertTrue(prompt.contains("- metadata:\n  - file_id: abc123"))
        assertTrue(prompt.contains("  - width: 1280"))
        assertTrue(prompt.contains("- visual content: call `describeRepliedPhoto`"))
        assertFalse(prompt.contains("<text_caption>"))
    }

    @Test
    fun `isReplyToOtherUser skips replies to the bot`() {
        assertFalse(isReplyToOtherUser(replyAuthorId = 123, botUserId = 123))
        assertTrue(isReplyToOtherUser(replyAuthorId = 456, botUserId = 123))
        assertTrue(isReplyToOtherUser(replyAuthorId = null, botUserId = 123))
    }

    @Test
    fun `formatHistoryInput keeps compact replied text context`() {
        val historyText =
            formatHistoryInput(
                currentMessageText = "summarize this article and send it as a markdown file",
                repliedMessage = RepliedMessageSummary(
                    type = "text",
                    textOrCaption = "https://example.com/article/4034",
                    metadata = listOf("file_id: file-1")
                )
            )

        assertTrue(historyText.contains("<reply_context>"))
        assertTrue(historyText.contains("- type: text"))
        assertTrue(historyText.contains("- metadata:\n  - file_id: file-1"))
        assertTrue(historyText.contains("https://example.com/article/4034"))
        assertTrue(historyText.contains("<text_caption>"))
        assertTrue(historyText.contains("</text_caption>"))
        assertTrue(historyText.contains("</reply_context>"))
        assertTrue(historyText.contains("<user_message>"))
        assertTrue(historyText.contains("summarize this article"))
        assertTrue(historyText.contains("</user_message>"))
    }
}
