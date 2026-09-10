package com.helltar.vusan.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("unused")
private class CoreTestTools : ToolSet {

    @Tool
    @LLMDescription("Sends text to the user.")
    fun sendTestMessage(@LLMDescription("The text to send.") text: String): String = text
}

@Suppress("unused")
private class DrawTestTools : ToolSet {

    @Tool
    @LLMDescription("Draws a picture from a description.")
    fun drawTestPicture(@LLMDescription("What to draw.") subject: String): String = subject
}

@Suppress("unused")
private class SpeakTestTools : ToolSet {

    @Tool
    @LLMDescription("Says text out loud.")
    fun speakTestText(@LLMDescription("The text to say.") text: String): String = text
}

class ToolCatalogTest {

    private fun catalog(): ToolCatalog =
        toolCatalog {
            tools(CoreTestTools())
            tools(ToolGroup.IMAGE_GENERATION, DrawTestTools())
            tools(ToolGroup.VOICE_REPLIES, SpeakTestTools())
        }

    private fun ToolCatalog.visibleNames(): List<String> = visibleDescriptors().map { it.name }

    @Test
    fun `a deferred group is registered but not offered until it is loaded`() {
        val catalog = catalog()

        assertEquals(listOf("sendTestMessage", "loadTools"), catalog.visibleNames())
        assertContains(catalog.registry.tools.map { it.name }, "drawTestPicture")
    }

    @Test
    fun `loading a group offers its tools and widens the revision`() {
        val catalog = catalog()
        val before = catalog.revision

        val result = catalog.load(listOf(" Image_Generation "))

        assertEquals(listOf(ToolGroup.IMAGE_GENERATION), result.groups)
        assertEquals(listOf("drawTestPicture"), result.toolNames)
        assertTrue(result.unknown.isEmpty())
        assertContains(catalog.visibleNames(), "drawTestPicture")
        assertFalse("speakTestText" in catalog.visibleNames())
        assertTrue(catalog.revision > before)
    }

    // the run re-sends its tool list on a new revision, so a repeat must not look like a widening
    @Test
    fun `loading the same group twice reports it without widening again`() {
        val catalog = catalog()
        catalog.load(listOf("image_generation"))
        val revision = catalog.revision

        val result = catalog.load(listOf("image_generation"))

        assertEquals(listOf("drawTestPicture"), result.toolNames)
        assertEquals(revision, catalog.revision)
    }

    @Test
    fun `an unknown group is reported and changes nothing`() {
        val catalog = catalog()
        val visible = catalog.visibleNames()

        val result = catalog.load(listOf("image_generation", "teleportation", ""))

        assertEquals(listOf(ToolGroup.IMAGE_GENERATION), result.groups)
        assertEquals(listOf("teleportation"), result.unknown)
        assertEquals(visible + "drawTestPicture", catalog.visibleNames())
    }

    @Test
    fun `the menu describes every group this turn registered and nothing else`() {
        val menu = catalog().menu().orEmpty()

        assertContains(menu, "- `image_generation` — ${ToolGroup.IMAGE_GENERATION.summary}")
        assertContains(menu, "- `voice_replies` — ${ToolGroup.VOICE_REPLIES.summary}")
        assertFalse("youtube" in menu)
    }

    // a chat can lose every optional group with the service behind it; the loader would then only cost
    // schema space and offer a menu of nothing.
    @Test
    fun `a catalog with nothing deferred has no menu and no loader`() {
        val catalog = toolCatalog { tools(CoreTestTools()) }

        assertNull(catalog.menu())
        assertEquals(listOf("sendTestMessage"), catalog.visibleNames())
        assertEquals(listOf("sendTestMessage"), catalog.registry.tools.map { it.name })
    }

    @Test
    fun `group names are the lowercase spelling the model is told to use`() {
        assertEquals(
            listOf("image_generation", "voice_replies"),
            catalog().groupNames()
        )
    }
}
