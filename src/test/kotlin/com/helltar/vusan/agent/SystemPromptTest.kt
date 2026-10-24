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
        val prompt = systemPromptFor("Custom personality", MODEL_ID, listOf("VusanBot"), "Vusan")

        assertContains(prompt, """Your Telegram account is `@VusanBot`, shown as "Vusan".""")
        assertContains(prompt, "it is removed from the text you receive")
    }

    @Test
    fun `system prompt names every handle, the one the profile shows first`() {
        val prompt = systemPromptFor("Custom personality", MODEL_ID, listOf("VusanAgent", "VusanBot"), "Vusan")

        assertContains(prompt, """Your Telegram account is `@VusanAgent` (also `@VusanBot`), shown as "Vusan".""")
    }

    @Test
    fun `system prompt says nothing about an account it does not know`() {
        val prompt = systemPromptFor("Custom personality", MODEL_ID, botUsernames = emptyList(), botDisplayName = "Vusan")

        assertFalse("Telegram account" in prompt)
        assertFalse("Vusan" in prompt.substringAfter("</personality>"))
    }

    // the rule is fixed, the menu it points at is not: `<tool_groups>` changes with what the
    // conversation already loaded, and this block is the one prefix every request shares.
    @Test
    fun `system prompt states the loading rule without carrying the menu itself`() {
        val prompt = systemPromptFor("Custom personality", MODEL_ID)

        assertContains(prompt, "# Loading more tools")
        assertContains(prompt, "once `loadTools` names the group")
        assertContains(prompt, "Never tell the user it is unavailable")
        assertFalse("</tool_groups>" in prompt)
    }

    // a deployment without a sandbox would only be offered references that fail
    @Test
    fun `the sandbox is named only to a deployment that has one`() {
        val with = systemPromptFor("Custom personality", MODEL_ID, sandbox = true)
        val without = systemPromptFor("Custom personality", MODEL_ID)

        assertContains(with, "# Passing results between tools")
        assertContains(with, "`sandbox:<path>`")
        assertContains(with, "`turns/<when>/`")
        assertContains(without, "# Passing results between tools")
        assertFalse("sandbox:" in without)
        assertFalse("turns/" in without)
    }

    // without it a model asked again answers from the history of when it was on, with a made-up refusal
    @Test
    fun `media generation switched off is named, and nothing is said where it is on`() {
        val off = systemPromptFor("Custom personality", MODEL_ID, images = false, videos = true)
        val bothOff = systemPromptFor("Custom personality", MODEL_ID)
        val on = systemPromptFor("Custom personality", MODEL_ID, images = true, videos = true)

        assertContains(off, "Switched off on this deployment, with no tool for it and none to load: drawing and editing pictures.")
        assertContains(off, "never make up a result or a refusal")
        assertContains(bothOff, "none to load: drawing and editing pictures, making and editing videos.")
        assertFalse("Switched off" in on)
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
        assertContains(prompt, "`/tasks` opens the user's scheduled tasks")
        assertContains(prompt, "`/clear` clears the user's conversation history and leaves durable memory and scheduled tasks as they are")
        assertContains(prompt, "`/stop` interrupts what you are doing")
        assertContains(prompt, "These commands bypass you")
    }

    // said outright, since a model told only to format in html answered in markdown fences with a bare
    // `<` inside, which the parse mode refuses
    @Test
    fun `system prompt keeps code out of markdown`() {
        val prompt = systemPromptFor("Custom personality", MODEL_ID)

        assertContains(prompt, "Code goes in `<code>` inline and in `<pre>` as a block, never in backticks or ``` fences")
        assertContains(prompt, "`if (x &lt; 5 &amp;&amp; y &gt; 0)`")
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
