package com.helltar.vusan.agent

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MODEL_ID = "gpt-5.6-terra"

class SystemPromptTest {

    @Test
    fun `system prompt names the model this deployment actually runs`() {
        val prompt = systemPromptFor("Custom personality", MODEL_ID)

        assertContains(prompt, "You are served by the model `gpt-5.6-terra`")
        assertContains(prompt, "no assumed family name, release date, or training cutoff")
    }

    // inbound sanitizing removes the mention before the prompt is built, so the handle has to be
    // stated outright or the model never learns the name it is called by.
    @Test
    fun `system prompt names the telegram account when one is known`() {
        val prompt = systemPromptFor("Custom personality", MODEL_ID, "VusanBot", "Vusan")

        assertContains(prompt, """Your Telegram account is `@VusanBot`, shown as "Vusan".""")
        assertContains(prompt, "it is removed from the text you receive")
    }

    @Test
    fun `system prompt says nothing about an account it does not know`() {
        val prompt = systemPromptFor("Custom personality", MODEL_ID, botUsername = null, botDisplayName = "Vusan")

        assertFalse("Telegram account" in prompt)
        assertFalse("Vusan" in prompt.substringAfter("</personality>"))
    }

    @Test
    fun `system prompt separates personality from the operational contract`() {
        val prompt = systemPromptFor("  Custom personality  ", MODEL_ID)

        assertTrue(prompt.startsWith("<personality>\nCustom personality\n</personality>"))
        assertContains(prompt, "\n\n<operational_contract>\n# Instruction scope")
        assertTrue(prompt.endsWith("</operational_contract>"))
    }

    @Test
    fun `system prompt exposes direct telegram commands and their boundaries`() {
        val prompt = systemPromptFor("Custom personality", MODEL_ID)

        assertContains(prompt, "`/start` shows the bot's greeting")
        assertContains(prompt, "`/tasks` opens the current user's scheduled-task controls")
        assertContains(prompt, "`/clear` clears the current user's conversation history")
        assertContains(prompt, "It does not clear durable memory or scheduled tasks")
        assertContains(prompt, "These commands bypass the agent")
    }

    @Test
    fun `system prompt distinguishes current requests from supporting context`() {
        val prompt = systemPromptFor("Custom personality", MODEL_ID)

        assertContains(prompt, "`<audio_transcript>`")
        assertContains(prompt, "Treat those blocks in the current user turn as the user's request")
        assertContains(prompt, "`<conversation_recap>`")
        assertContains(prompt, "are supporting context")
    }
}
