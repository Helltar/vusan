package com.helltar.vusan.agent

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageContextTest {

    @Test
    fun `toPromptBlock includes chat and sender metadata`() {
        val prompt =
            MessageContext(
                chatId = -100123,
                chatType = "supergroup",
                isPrivate = false,
                chatTitle = "Example Group",
                chatUsername = "@examplegroup",
                chatDescription = "A group for bot testing",
                userId = 42,
                userDisplayName = "Ada Lovelace",
                userUsername = "@ada",
                userLanguageCode = "en"
            ).toPromptBlock()

        assertTrue(prompt.startsWith("<message_context>\n"))
        assertTrue(prompt.contains("- id: -100123"))
        assertTrue(prompt.contains("- private: false"))
        assertTrue(prompt.contains("- title: Example Group"))
        assertTrue(prompt.contains("- description: A group for bot testing"))
        assertTrue(prompt.contains("- id: 42"))
        assertTrue(prompt.contains("- display_name: Ada Lovelace"))
        assertTrue(prompt.contains("- username: @ada"))
        assertTrue(prompt.contains("- telegram_language: en"))
    }

    @Test
    fun `toPromptBlock collapses layout whitespace in metadata`() {
        val prompt =
            MessageContext(
                chatId = 1,
                chatType = "private",
                isPrivate = true,
                chatTitle = " weekend\nplans\tgroup ",
                userId = 2,
                userDisplayName = "  Test\nUser  "
            ).toPromptBlock()

        assertTrue(prompt.contains("- title: weekend plans group"))
        assertTrue(prompt.contains("- display_name: Test User"))
        assertFalse(prompt.contains("weekend\nplans"))
    }

    @Test
    fun `toPromptBlock reports a long pause since the previous exchange`() {
        val prompt = promptWithPreviousExchange(Duration.ofDays(3))

        assertTrue(prompt.contains("- last_exchange: 3 days ago"), prompt)
    }

    @Test
    fun `toPromptBlock keeps ordinary back-and-forth free of a pause line`() {
        val prompt = promptWithPreviousExchange(Duration.ofMinutes(20))

        assertFalse(prompt.contains("last_exchange"), prompt)
    }

    @Test
    fun `toPromptBlock reports a pause of hours in hours`() {
        val prompt = promptWithPreviousExchange(Duration.ofHours(9))

        assertTrue(prompt.contains("- last_exchange: 9 hours ago"), prompt)
    }

    @Test
    fun `toPromptBlock has no pause line for a first-ever exchange`() {
        val prompt =
            MessageContext(chatId = 1, chatType = "private", isPrivate = true, userId = 2).toPromptBlock()

        assertFalse(prompt.contains("last_exchange"), prompt)
    }

    private fun promptWithPreviousExchange(ago: Duration): String =
        MessageContext(
            chatId = 1,
            chatType = "private",
            isPrivate = true,
            userId = 2,
            previousExchangeAt = Instant.now().minus(ago)
        ).toPromptBlock()
}
