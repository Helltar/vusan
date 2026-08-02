package com.helltar.vusan.agent

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
}
