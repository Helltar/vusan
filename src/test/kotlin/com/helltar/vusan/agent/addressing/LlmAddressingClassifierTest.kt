package com.helltar.vusan.agent.addressing

import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.FakeLlmClient
import com.helltar.vusan.llm.TEST_MODEL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmAddressingClassifierTest {

    @Test
    fun `the question names the bot and every other spelling the chat uses`() {
        val prompt = addressingSystemPrompt(listOf("Robin", "robbie", "rob"), busy = false)

        assertTrue(prompt.contains("the chat bot Robin (written «robbie», «rob» in the chat)"), prompt)
    }

    @Test
    fun `a spelling that differs only in case is not listed as another name`() {
        val prompt = addressingSystemPrompt(listOf("Robin", "robin"), busy = false)

        assertTrue(prompt.contains("the chat bot Robin, so that"), prompt)
    }

    @Test
    fun `the note about a waiting author is there only while one is waiting`() {
        assertFalse(addressingSystemPrompt(listOf("Robin"), busy = false).contains("bot_is_working_on_request_from"))
        assertTrue(addressingSystemPrompt(listOf("Robin"), busy = true).contains("bot_is_working_on_request_from"))
    }

    @Test
    fun `the state carries the lines, the message and nothing it was not given`() {
        val state =
            addressingState(
                AddressingInput(
                    botNames = listOf("Robin", "robbie"),
                    recent = listOf(ChatLine("Alice", "anyone up for lunch"), ChatLine("Robin", "I am, in spirit")),
                    message = ChatLine("Bob", "robin what about you"),
                ),
            )

        assertEquals(listOf("Robin", "robbie"), state.strings("bot_names"))
        assertEquals(listOf("Alice", "Robin"), state.lines().map { it.string("from") })
        assertEquals("robin what about you", state.message().string("text"))
        assertFalse("in_reply_to_message_from" in state.message())
        assertFalse("bot_is_working_on_request_from" in state)
    }

    @Test
    fun `a reply to a person and a waiting author are both spelled out`() {
        val state =
            addressingState(
                AddressingInput(
                    botNames = listOf("Robin"),
                    recent = emptyList(),
                    message = ChatLine("Bob", "and how is that going"),
                    inReplyTo = "Alice",
                    botBusyFor = "Bob",
                ),
            )

        assertEquals("Alice", state.message().string("in_reply_to_message_from"))
        assertEquals("Bob", state["bot_is_working_on_request_from"]?.jsonPrimitive?.content)
    }

    @Test
    fun `every line is capped the way it was measured`() {
        val state =
            addressingState(
                AddressingInput(
                    botNames = listOf("Robin"),
                    recent = listOf(ChatLine("Alice", "a".repeat(2_000))),
                    message = ChatLine("Bob", "b".repeat(2_000)),
                ),
            )

        assertTrue(state.lines().single().string("text").length <= 500)
        assertTrue(state.message().string("text").length <= 500)
    }

    @Test
    fun `the verdict is read out of the answer however it is wrapped`() {
        assertEquals(true, parseAddressingVerdict("""{"addressed": true}"""))
        assertEquals(false, parseAddressingVerdict("""{"addressed":false}"""))
        assertEquals(true, parseAddressingVerdict("```json\n{ \"addressed\" : true }\n```"))
    }

    @Test
    fun `an answer with no verdict in it is no verdict`() {
        assertNull(parseAddressingVerdict("yes"))
        assertNull(parseAddressingVerdict("""{"addressed": "maybe"}"""))
        assertNull(parseAddressingVerdict(""))
    }

    @Test
    fun `the classifier sends the wording and the state and reads the verdict back`() = runBlocking {
        val executor = FakeLlmClient(response = """{"addressed": true}""")
        val classifier = LlmAddressingClassifier(executor, TEST_MODEL, RequestOptions())

        val verdict =
            classifier.isAddressed(
                AddressingInput(botNames = listOf("Robin"), recent = emptyList(), message = ChatLine("Bob", "robin hi")),
            )

        assertEquals(true, verdict)
        assertTrue(executor.promptText.contains("Is new_message said TO the chat bot Robin"))
        assertTrue(executor.promptText.contains(""""text":"robin hi""""))
    }

    private fun JsonObject.strings(key: String): List<String> =
        getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.lines(): List<JsonObject> =
        getValue("recent_messages").jsonArray.map { it.jsonObject }

    private fun JsonObject.message(): JsonObject =
        getValue("new_message").jsonObject

    private fun JsonObject.string(key: String): String =
        getValue(key).jsonPrimitive.content
}
