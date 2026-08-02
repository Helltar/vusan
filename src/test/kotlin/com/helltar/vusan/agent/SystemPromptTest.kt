package com.helltar.vusan.agent

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class SystemPromptTest {

    @Test
    fun `system prompt separates personality from the operational contract`() {
        val prompt = systemPromptFor("  Custom personality  ")

        assertTrue(prompt.startsWith("<personality>\nCustom personality\n</personality>"))
        assertContains(prompt, "\n\n<operational_contract>\n# Instruction scope")
        assertTrue(prompt.endsWith("</operational_contract>"))
    }

    @Test
    fun `system prompt exposes direct telegram commands and their boundaries`() {
        val prompt = systemPromptFor("Custom personality")

        assertContains(prompt, "`/start` shows the bot's greeting")
        assertContains(prompt, "`/tasks` opens the current user's scheduled-task controls")
        assertContains(prompt, "`/clear` clears the current user's conversation history")
        assertContains(prompt, "It does not clear durable memory or scheduled tasks")
        assertContains(prompt, "These commands bypass the agent")
    }
}
