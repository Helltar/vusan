package com.helltar.vusan.tools.catalog

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.ToolCatalog
import com.helltar.vusan.tools.ToolGroup
import com.helltar.vusan.tools.toolCatalog
import com.helltar.vusan.tools.toolFailure
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private class DrawTestTools : ToolSet {

    @Tool("Draws a picture from a description.")
    fun drawTestPicture(@Arg("What to draw.") subject: String): String = subject
}

private class SpeakTestTools : ToolSet {

    @Tool("Says text out loud.")
    fun speakTestText(@Arg("The text to say.") text: String): String = text
}

class CatalogToolsTest {

    private fun catalog(): ToolCatalog =
        toolCatalog {
            tools(ToolGroup.GIFS, DrawTestTools())
            tools(ToolGroup.VOICE_REPLIES, SpeakTestTools())
        }

    @Test
    fun `loadTools loads every named group at once and names what it loaded`() = runBlocking {
        val catalog = catalog()

        val result = CatalogTools(catalog).loadTools("gifs, voice_replies")

        assertContains(result, "`gifs`")
        assertContains(result, "`voice_replies`")
        assertContains(result, "drawTestPicture, speakTestText")
        assertContains(catalog.visibleDefinitions().map { it.name }, "speakTestText")
    }

    // the tools of a group that did load are still worth having, so the call reports the bad name
    // alongside them instead of failing the whole batch.
    @Test
    fun `loadTools loads what it recognizes and reports the rest`() = runBlocking {
        val result = CatalogTools(catalog()).loadTools("gifs,teleportation")

        assertContains(result, "drawTestPicture")
        assertContains(result, "no such group: teleportation")
        assertContains(result, "The groups are: voice_replies")
    }

    @Test
    fun `loadTools fails when no name matches, listing the groups that exist`() = runBlocking {
        val catalog = catalog()

        val message = toolFailure { CatalogTools(catalog).loadTools("teleportation") }

        assertContains(message, "no tool group is named teleportation")
        assertContains(message, "gifs, voice_replies")
        assertTrue(catalog.visibleDefinitions().map { it.name }.none { it == "drawTestPicture" })
    }

    @Test
    fun `loadTools rejects an empty request`() = runBlocking {
        assertEquals(
            "Tool failed: Groups must not be empty",
            toolFailure { CatalogTools(catalog()).loadTools("   ") },
        )
    }
}
