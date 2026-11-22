package com.helltar.vusan.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PromptExportTest {

    @Test
    fun `the export carries the prompt and every tool the deployment registers`() {
        val export = exportPrompt(PromptExportOptions(model = "test-model"))
        val system = export.text("system")
        val operational = export.text("operational")

        assertTrue(system.startsWith("<personality>\n"))
        assertTrue(system.endsWith("$operational\n</operational_contract>"))
        assertTrue(operational.startsWith("# Instruction scope"))
        assertTrue("\n\n# Passing results between tools\n" in operational)
        assertTrue("`test-model`" in operational)

        val names = export.tools().map { it.text("name") }
        assertTrue(listOf("sendMessage", "runCommand", "generateImage", "generateVideo", "webSearch", "loadTools").all { it in names })
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `a grouped tool names its group and a visible one none`() {
        val tools = exportPrompt(PromptExportOptions()).tools().associateBy { it.text("name") }

        assertEquals("youtube", tools.getValue("downloadVideo").text("group"))
        assertEquals("null", tools.getValue("sendMessage")["group"].toString())
    }

    @Test
    fun `a deployment without a sandbox is offered no sandbox tools and told of none`() {
        val export = exportPrompt(PromptExportOptions(sandbox = false, videos = false))
        val names = export.tools().map { it.text("name") }

        assertFalse("runCommand" in names)
        assertFalse("generateVideo" in names)
        assertFalse("sandbox:<path>" in export.text("operational"))
    }

    @Test
    fun `each search provider is offered alone, with the tools it has`() {
        fun searchTools(provider: String) =
            exportPrompt(PromptExportOptions(search = provider)).tools().map { it.text("name") }
                .filter { it in setOf("webSearch", "searchImages", "extractPageContent", "answerFromWeb") }
                .toSet()

        assertEquals(setOf("webSearch", "searchImages", "extractPageContent"), searchTools("tavily"))
        assertEquals(setOf("webSearch", "searchImages"), searchTools("searxng"))
        assertEquals(setOf("webSearch", "extractPageContent"), searchTools("exa"))
        assertEquals(setOf("answerFromWeb"), searchTools("codex"))
        assertEquals(emptySet(), searchTools("none"))
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.tools(): List<JsonObject> = getValue("tools").jsonArray.map { it.jsonObject }
}
