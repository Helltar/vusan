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

    // the tool array is part of the cached prefix, so it may not depend on the order groups were loaded
    @Test
    fun `the visible order follows registration, not the order the groups were loaded`() {
        val loadedInMenuOrder = catalog().apply { load(listOf("image_generation", "voice_replies")) }
        val loadedInReverse = catalog().apply { load(listOf("voice_replies", "image_generation")) }

        assertEquals(
            listOf("sendTestMessage", "loadTools", "drawTestPicture", "speakTestText"),
            loadedInMenuOrder.visibleNames()
        )
        assertEquals(loadedInMenuOrder.visibleNames(), loadedInReverse.visibleNames())
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

    // what the conversation loaded last time is offered again from the first request: a tool array
    // that changes mid-turn rebuilds the whole cached prompt prefix.
    @Test
    fun `a preloaded group is offered from the start and drops out of the menu`() {
        val catalog =
            toolCatalog(preloaded = setOf(ToolGroup.IMAGE_GENERATION)) {
                tools(CoreTestTools())
                tools(ToolGroup.IMAGE_GENERATION, DrawTestTools())
                tools(ToolGroup.VOICE_REPLIES, SpeakTestTools())
            }

        assertContains(catalog.visibleNames(), "drawTestPicture")
        assertEquals(listOf("voice_replies"), catalog.groupNames())
        assertFalse("image_generation" in catalog.menu().orEmpty())
    }

    // a group the chat no longer has must not be resurrected by what an earlier turn remembered
    @Test
    fun `a preloaded group that is not registered this turn is ignored`() {
        val catalog =
            toolCatalog(preloaded = setOf(ToolGroup.YOUTUBE)) {
                tools(CoreTestTools())
                tools(ToolGroup.IMAGE_GENERATION, DrawTestTools())
            }

        assertEquals(listOf("sendTestMessage", "loadTools"), catalog.visibleNames())
        assertEquals(listOf("image_generation"), catalog.groupNames())
    }

    @Test
    fun `loading reports the groups so the next turn can offer them again`() {
        val loaded = mutableListOf<ToolGroup>()
        val catalog =
            toolCatalog(onLoad = { loaded += it }) {
                tools(CoreTestTools())
                tools(ToolGroup.IMAGE_GENERATION, DrawTestTools())
            }

        catalog.load(listOf("image_generation"))
        catalog.load(listOf("teleportation"))

        assertEquals(listOf(ToolGroup.IMAGE_GENERATION), loaded)
    }

    @Test
    fun `a catalog whose every group is preloaded offers no loader`() {
        val catalog =
            toolCatalog(preloaded = setOf(ToolGroup.IMAGE_GENERATION)) {
                tools(CoreTestTools())
                tools(ToolGroup.IMAGE_GENERATION, DrawTestTools())
            }

        assertNull(catalog.menu())
        assertEquals(listOf("sendTestMessage", "drawTestPicture"), catalog.visibleNames())
        assertFalse("loadTools" in catalog.registry.tools.map { it.name })
    }

    @Test
    fun `group names are the lowercase spelling the model is told to use`() {
        assertEquals(
            listOf("image_generation", "voice_replies"),
            catalog().groupNames()
        )
    }
}
