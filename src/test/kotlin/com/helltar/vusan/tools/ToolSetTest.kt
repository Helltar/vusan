package com.helltar.vusan.tools

import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("FunctionOnlyReturningConstant")
private class SampleTools : ToolSet {

    @Tool("Creates a poll.")
    fun createPoll(
        @Arg("The question.") question: String,
        @Arg("The options.") options: List<String>,
        isAnonymous: Boolean = true,
        maxVotes: Int = 1,
    ): String = "$question ${options.joinToString("|")} $isAnonymous $maxVotes"

    @Tool("Looks something up.", readOnly = true)
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
    fun `a tool is read-only only when its annotation says so`() {
        assertTrue(tool("lookUp").readOnly)
        assertFalse(tool("createPoll").readOnly)
        assertFalse(tool("ping").readOnly)
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
    fun `a number spelled as text and a text spelled as a number are both read`() = runBlocking {
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

    @Test
    fun `a text argument that is a reference arrives as what it points at`() = runBlocking {
        val tool = ReferenceTools().toolFunctions().single { it.name == "say" }

        assertEquals("the whole earlier answer", withContext(FakeReferences) { tool.call(args("""{"text":"#1"}""")) })
        assertEquals("as written", withContext(FakeReferences) { tool.call(args("""{"text":"as written"}""")) })
        assertEquals("#1", tool.call(args("""{"text":"#1"}""")))
    }

    // references are opt-in: anywhere else a value that reads like one is meant as written
    @Test
    fun `a text argument that does not take references keeps the value as written`() = runBlocking {
        val tool = ReferenceTools().toolFunctions().single { it.name == "open" }

        assertEquals("#1", withContext(FakeReferences) { tool.call(args("""{"path":"#1"}""")) })
    }

    @Test
    fun `a file argument takes a reference, alone or in a list`() = runBlocking {
        val tools = ReferenceTools().toolFunctions()

        assertEquals("cat.png", withContext(FakeReferences) { tools.single { it.name == "look" }.call(args("""{"file":"#1/1"}""")) })
        assertEquals("cat.png,cat.png", withContext(FakeReferences) { tools.single { it.name == "merge" }.call(args("""{"files":["#1/1","#1/1"]}""")) })
        assertEquals("nothing", withContext(FakeReferences) { tools.single { it.name == "look" }.call(args("""{}""")) })
    }

    @Test
    fun `a file argument outside a turn has nothing to resolve against`() = runBlocking {
        val tool = ReferenceTools().toolFunctions().single { it.name == "look" }

        assertContains(assertFailsWith<IllegalArgumentException> { tool.call(args("""{"file":"#1/1"}""")) }.message.orEmpty(), "file")
    }

    @Test
    fun `a file parameter reads to the model as text`() {
        val schema = ReferenceTools().toolFunctions().single { it.name == "merge" }.parameters

        assertEquals("array", schema["properties"]!!.jsonObject["files"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
}

@Suppress("FunctionOnlyReturningConstant")
private class ReferenceTools : ToolSet {

    @Tool("Says something.")
    fun say(@Arg("What.", takesReference = true) text: String): String = text

    @Tool("Opens a path.")
    fun open(@Arg("Where.") path: String): String = path

    @Tool("Looks at a file.")
    fun look(@Arg("Which.") file: AttachedFile? = null): String = file?.name ?: "nothing"

    @Tool("Merges files.")
    fun merge(@Arg("Which.") files: List<AttachedFile>): String = files.joinToString(",") { it.name }
}

private object FakeReferences : CallShelf {

    override suspend fun textOrNull(value: String): String? = if (value == "#1") "the whole earlier answer" else null

    override suspend fun file(value: String): AttachedFile {
        require(value == "#1/1") { "no file `$value`" }

        return AttachedFile(name = "cat.png", fileSizeBytes = 1, mimeType = "image/png", kind = AttachedFileKind.IMAGE, loadBytes = { byteArrayOf(1) })
    }

    override suspend fun keep(name: String, bytes: ByteArray): String? = null
}
