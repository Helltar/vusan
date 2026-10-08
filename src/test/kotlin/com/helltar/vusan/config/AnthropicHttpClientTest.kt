package com.helltar.vusan.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnthropicHttpClientTest {

    @Test
    fun `a request that asks for automatic caching gets a breakpoint on its last system block too`() {
        val body =
            """{"model":"claude-opus-5-5","cache_control":{"type":"ephemeral","ttl":"1h"},""" +
                    """"system":[{"type":"text","text":"one"},{"type":"text","text":"two"}],"messages":[]}"""

        val system = Json.parseToJsonElement(addAnthropicSystemCacheBreakpoint(body)).jsonObject.getValue("system").jsonArray

        assertNull(system[0].jsonObject["cache_control"])
        // the same ttl as the automatic breakpoint, which the api requires of the pair
        assertEquals("1h", system[1].jsonObject.getValue("cache_control").jsonObject.getValue("ttl").jsonPrimitive.content)
    }

    @Test
    fun `a request without automatic caching is left alone`() {
        val body = """{"model":"claude-opus-5-5","system":[{"type":"text","text":"one"}],"messages":[]}"""

        assertEquals(body, addAnthropicSystemCacheBreakpoint(body))
    }

    @Test
    fun `a system block that already carries a breakpoint keeps it`() {
        val body =
            """{"model":"claude-opus-5-5","cache_control":{"type":"ephemeral"},""" +
                    """"system":[{"type":"text","text":"one","cache_control":{"type":"ephemeral","ttl":"1h"}}],"messages":[]}"""

        assertEquals(body, addAnthropicSystemCacheBreakpoint(body))
    }

    @Test
    fun `a request without a system block has nothing to mark`() {
        val body = """{"model":"claude-opus-5-5","cache_control":{"type":"ephemeral"},"messages":[]}"""

        assertEquals(body, addAnthropicSystemCacheBreakpoint(body))
    }
}
