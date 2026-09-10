package com.helltar.vusan.tools.catalog

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.tools.ToolCatalog
import com.helltar.vusan.tools.ToolGroup
import com.helltar.vusan.tools.toolCatalog
import com.helltar.vusan.tools.toolFailure
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

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

class CatalogToolsTest {

    private fun catalog(): ToolCatalog =
        toolCatalog {
            tools(ToolGroup.IMAGE_GENERATION, DrawTestTools())
            tools(ToolGroup.VOICE_REPLIES, SpeakTestTools())
        }

    @Test
    fun `loadTools loads every named group at once and names what it loaded`() = runBlocking {
        val catalog = catalog()

        val result = CatalogTools(catalog).loadTools("image_generation, voice_replies")

        assertContains(result, "`image_generation`")
        assertContains(result, "`voice_replies`")
        assertContains(result, "drawTestPicture, speakTestText")
        assertContains(catalog.visibleDescriptors().map { it.name }, "speakTestText")
    }

    // the tools of a group that did load are still worth having, so the call reports the bad name
    // alongside them instead of failing the whole batch.
    @Test
    fun `loadTools loads what it recognizes and reports the rest`() = runBlocking {
        val result = CatalogTools(catalog()).loadTools("image_generation,teleportation")

        assertContains(result, "drawTestPicture")
        assertContains(result, "no such group: teleportation")
        assertContains(result, "image_generation, voice_replies")
    }

    @Test
    fun `loadTools fails when no name matches, listing the groups that exist`() = runBlocking {
        val catalog = catalog()

        val message = toolFailure { CatalogTools(catalog).loadTools("teleportation") }

        assertContains(message, "no tool group is named teleportation")
        assertContains(message, "image_generation, voice_replies")
        assertTrue(catalog.visibleDescriptors().map { it.name }.none { it == "drawTestPicture" })
    }

    @Test
    fun `loadTools rejects an empty request`() = runBlocking {
        assertEquals(
            "Tool failed: Groups must not be empty",
            toolFailure { CatalogTools(catalog()).loadTools("   ") }
        )
    }
}
