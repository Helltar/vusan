package com.helltar.vusan.agent.conversation

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
        assertEquals("""{"query":"news"}""", toolCallArgsForStorage("""{"query":"news"}"""))
    }

    @Test
    fun `blank args become an empty object`() {
        assertEquals("{}", toolCallArgsForStorage(""))
        assertEquals("{}", toolCallArgsForStorage("   "))
    }

    @Test
    fun `garbled and non-object args become an empty object`() {
        assertEquals("{}", toolCallArgsForStorage("""{"query":"new"""))
        assertEquals("{}", toolCallArgsForStorage("[1,2,3]"))
        assertEquals("{}", toolCallArgsForStorage("\"just a string\""))
    }

    @Test
    fun `significant whitespace inside a value is not collapsed`() {
        val args = jsonArgs("code" to "def f():\n        return 1")

        assertEquals("def f():\n        return 1", codeValue(toolCallArgsForStorage(args)))
    }

    @Test
    fun `a long script value is truncated but stays valid JSON`() {
        val longCode = "x = 1\n".repeat(4_000) // ~24k chars, well over the cap
        val result = toolCallArgsForStorage(jsonArgs("code" to longCode))

        // still a parseable JSON object that an OpenAI-compatible endpoint will accept.
        val code = codeValue(result)
        assertTrue(code.length < longCode.length, "expected the long value to be truncated")
        assertTrue(code.endsWith("[truncated]"), "expected a truncation marker")
    }

    @Test
    fun `re-applying the helper is idempotent`() {
        val once = toolCallArgsForStorage(jsonArgs("code" to "x = 1\n".repeat(4_000)))

        assertEquals(once, toolCallArgsForStorage(once))
    }

    @Test
    fun `nested and repeated values have a global serialized cap`() {
        val raw =
            buildJsonObject {
                repeat(20) { index ->
                    put("field-$index", buildJsonObject { put("nested", JsonPrimitive("x".repeat(4_000))) })
                }
            }.toString()

        val result = toolCallArgsForStorage(raw)

        assertTrue(result.length <= 4_000)
        Json.parseToJsonElement(result).jsonObject
    }

    private fun jsonArgs(vararg pairs: Pair<String, String>): String =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString()

    private fun codeValue(args: String): String =
        (Json.parseToJsonElement(args).jsonObject.getValue("code") as JsonPrimitive).content
}
