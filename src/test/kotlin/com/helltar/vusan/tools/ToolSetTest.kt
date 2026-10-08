package com.helltar.vusan.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("unused", "FunctionOnlyReturningConstant")
private class SampleTools : ToolSet {

    @Tool("Creates a poll.")
    fun createPoll(
        @Arg("The question.") question: String,
        @Arg("The options.") options: List<String>,
        isAnonymous: Boolean = true,
        maxVotes: Int = 1,
    ): String = "$question ${options.joinToString("|")} $isAnonymous $maxVotes"

    @Tool("Looks something up.")
    suspend fun lookUp(@Arg("What to find.") query: String, limit: Long, focus: String? = null): String =
        "$query $limit ${focus ?: "none"}"

    @Tool("Takes nothing.")
    fun ping(): String = "pong"

    fun notATool(): String = "hidden"
}

@Suppress("FunctionOnlyReturningConstant")
private class BadReturnTools : ToolSet {

    @Tool("Returns the wrong type.")
    fun count(): Int = 1
}

class ToolSetTest {

    private val tools = SampleTools().toolFunctions()

    private fun tool(name: String) = tools.single { it.name == name }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `only annotated methods are tools, sorted by name`() {
        assertEquals(listOf("createPoll", "lookUp", "ping"), tools.map { it.name })
    }

    @Test
    fun `the schema marks parameters without a default and non-nullable as required`() {
        val poll = tool("createPoll")

        assertEquals(listOf("question", "options"), poll.requiredParameters)
        assertEquals(listOf("query", "limit"), tool("lookUp").requiredParameters)
        assertEquals(emptyList(), tool("ping").requiredParameters)

        val properties = poll.parameters.getValue("properties").jsonObject
        assertEquals("string", properties.getValue("question").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("array", properties.getValue("options").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("boolean", properties.getValue("isAnonymous").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("integer", properties.getValue("maxVotes").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("The question.", properties.getValue("question").jsonObject.getValue("description").jsonPrimitive.content)
        assertEquals(listOf("question", "options"), poll.parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content })

        val focus = tool("lookUp").parameters.getValue("properties").jsonObject.getValue("focus").jsonObject
        assertEquals(listOf("string", "null"), focus.getValue("type").jsonArray.map { it.jsonPrimitive.content }, "a nullable parameter may be sent as null")
    }

    @Test
    fun `the definition carries the description the model reads`() {
        assertEquals("Creates a poll.", tool("createPoll").definition.description)
        assertEquals("createPoll", tool("createPoll").definition.name)
    }

    @Test
    fun `arguments are decoded by type and defaults fill what the model left out`() = runBlocking {
        val result = tool("createPoll").call(args("""{"question":"Tea?","options":["yes","no"]}"""))

        assertEquals("Tea? yes|no true 1", result)
    }

    @Test
    fun `a number spelt as text and a text spelt as a number are both read`() = runBlocking {
        assertEquals("Tea? a|b false 3", tool("createPoll").call(args("""{"question":"Tea?","options":["a","b"],"isAnonymous":"false","maxVotes":"3"}""")))
        assertEquals("5 7 none", tool("lookUp").call(args("""{"query":5,"limit":7}""")))
    }

    @Test
    fun `a nullable parameter may be left out or sent as null`() = runBlocking {
        assertEquals("x 1 none", tool("lookUp").call(args("""{"query":"x","limit":1,"focus":null}""")))
        assertEquals("x 1 deep", tool("lookUp").call(args("""{"query":"x","limit":1,"focus":"deep"}""")))
    }

    @Test
    fun `a missing required argument and a value of the wrong shape are rejected`() = runBlocking {
        val missing = assertFailsWith<IllegalArgumentException> { tool("lookUp").call(args("""{"limit":1}""")) }
        assertContains(missing.message.orEmpty(), "query")

        val wrong = assertFailsWith<IllegalArgumentException> { tool("lookUp").call(args("""{"query":"x","limit":"many"}""")) }
        assertContains(wrong.message.orEmpty(), "limit")

        val notAList = assertFailsWith<IllegalArgumentException> { tool("createPoll").call(args("""{"question":"q","options":"yes"}""")) }
        assertContains(notAList.message.orEmpty(), "options")
    }

    @Test
    fun `a tool that takes nothing runs on an empty object`() = runBlocking {
        assertEquals("pong", tool("ping").call(buildJsonObject {}))
    }

    @Test
    fun `a tool that does not return text is refused when the set is read`() {
        assertTrue(assertFailsWith<IllegalArgumentException> { BadReturnTools().toolFunctions() }.message.orEmpty().contains("count"))
    }

    @Test
    fun `an array argument is read into a list of text`() = runBlocking {
        val result = tool("createPoll").call(buildJsonObject { put("question", "q"); putJsonArray("options") { add(kotlinx.serialization.json.JsonPrimitive(1)); add(kotlinx.serialization.json.JsonPrimitive("two")) } })

        assertEquals("q 1|two true 1", result)
    }
}
