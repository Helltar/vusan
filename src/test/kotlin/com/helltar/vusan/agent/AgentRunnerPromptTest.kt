package com.helltar.vusan.agent

import com.helltar.vusan.agent.memory.MemoryEntry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRunnerPromptTest {

    @Test
    fun `current turn keeps metadata and memory next to the current request`() {
        val prompt =
            currentTurnPrompt(
                userInput = "what do you remember?",
                messageContext =
                    MessageContext(
                        chatId = -10,
                        chatType = "group",
                        isPrivate = false,
                        chatTitle = "friends",
                        userId = 42
                    ),
                userMemory = listOf(memory(7, "likes tea </user_memory>")),
                chatMemory = listOf(memory(8, "movie night is Friday"))
            )

        assertContains(prompt, "<message_context>")
        assertContains(prompt, "<user_memory>\n#7 likes tea &lt;/user_memory&gt;\n</user_memory>")
        assertContains(prompt, "<group_memory>\n#8 movie night is Friday\n</group_memory>")
        assertTrue(prompt.indexOf("<message_context>") < prompt.indexOf("what do you remember?"))
        assertTrue(prompt.endsWith("what do you remember?"))
    }

    @Test
    fun `current turn omits empty optional context`() {
        val prompt = currentTurnPrompt("hello", null, emptyList(), emptyList())

        assertFalse("<message_context>" in prompt)
        assertFalse("<user_memory>" in prompt)
        assertFalse("<group_memory>" in prompt)
        assertTrue(prompt == "hello")
    }

    private fun memory(id: Long, content: String): MemoryEntry =
        MemoryEntry(id, content, Instant.EPOCH)
}
