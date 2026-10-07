package com.helltar.vusan.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class CodexPrefixDriftTest {

    private val drift = CodexPrefixDrift()

    @Test
    fun `a request that only appends has not drifted`() {
        assertEquals("items=[3] drift=[first]", drift.describe("s", request(SYSTEM, user("one"))))
        assertEquals("items=[5] drift=[none]", drift.describe("s", request(SYSTEM, user("one"), assistant("ok"), user("two"))))
    }

    @Test
    fun `a changed item is named by its place, kind and opening block`() {
        drift.describe("s", request(SYSTEM, user("<conversation_recap>\nold\n</conversation_recap>"), user("one")))

        assertEquals(
            "items=[4] drift=[2 of 4: message/user <conversation_recap>]",
            drift.describe("s", request(SYSTEM, user("<conversation_recap>\nnew\n</conversation_recap>"), user("one"))),
        )
    }

    @Test
    fun `a changed tool list is the front of the request`() {
        drift.describe("s", request(SYSTEM, user("one")))

        assertEquals(
            "items=[3] drift=[0 of 3: tools]",
            drift.describe("s", request(SYSTEM, user("one"), tools = """[{"name":"a"},{"name":"b"}]""")),
        )
    }

    @Test
    fun `a request that lost its tail says so, and conversations are kept apart`() {
        drift.describe("s", request(SYSTEM, user("one"), assistant("ok")))

        assertEquals("items=[3] drift=[3 of 4: shorter]", drift.describe("s", request(SYSTEM, user("one"))))
        assertEquals("items=[3] drift=[first]", drift.describe("other", request(SYSTEM, user("one"))))
    }

    private fun request(vararg input: String, tools: String = """[{"name":"a"}]""") =
        Json.parseToJsonElement("""{"tools":$tools,"input":[${input.joinToString(",")}]}""").jsonObject

    private fun user(text: String) = message("user", "input_text", text)

    private fun assistant(text: String) = message("assistant", "output_text", text)

    private fun message(role: String, type: String, text: String) =
        """{"type":"message","role":"$role","content":[{"type":"$type","text":${Json.encodeToString(text)}}]}"""

    private companion object {
        const val SYSTEM = """{"type":"message","role":"developer","content":[{"type":"input_text","text":"stable"}]}"""
    }
}
