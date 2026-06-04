package com.helltar.vusan.agent.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatTurnTest {

    @Test
    fun `valid object args are preserved`() {
        assertEquals("""{"query":"news"}""", toolCallArgsForHistory("""{"query":"news"}"""))
    }

    @Test
    fun `blank args become an empty object`() {
        assertEquals("{}", toolCallArgsForHistory(""))
        assertEquals("{}", toolCallArgsForHistory("   "))
    }

    @Test
    fun `garbled and non-object args become an empty object`() {
        assertEquals("{}", toolCallArgsForHistory("""{"query":"new"""))
        assertEquals("{}", toolCallArgsForHistory("[1,2,3]"))
        assertEquals("{}", toolCallArgsForHistory("\"just a string\""))
    }

    @Test
    fun `significant whitespace inside a value is not collapsed`() {
        val args = jsonArgs("code" to "def f():\n        return 1")

        assertEquals("def f():\n        return 1", codeValue(toolCallArgsForHistory(args)))
    }

    @Test
    fun `a long script value is truncated but stays valid JSON`() {
        val longCode = "x = 1\n".repeat(4_000) // ~24k chars, well over the cap
        val result = toolCallArgsForHistory(jsonArgs("code" to longCode))

        // Still a parseable JSON object that an OpenAI-compatible endpoint will accept.
        val code = codeValue(result)
        assertTrue(code.length < longCode.length, "expected the long value to be truncated")
        assertTrue(code.endsWith("[truncated]"), "expected a truncation marker")
    }

    @Test
    fun `re-applying the helper is idempotent`() {
        val once = toolCallArgsForHistory(jsonArgs("code" to "x = 1\n".repeat(4_000)))

        assertEquals(once, toolCallArgsForHistory(once))
    }

    private fun jsonArgs(vararg pairs: Pair<String, String>): String =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString()

    private fun codeValue(args: String): String =
        (Json.parseToJsonElement(args).jsonObject.getValue("code") as JsonPrimitive).content
}
