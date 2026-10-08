package com.helltar.vusan.llm

import kotlinx.serialization.json.JsonObject

/** One block of a message. */
sealed interface Part {

    data class Text(val text: String) : Part

    class Image(
        val bytes: ByteArray,
        val mimeType: String,
        val fileName: String? = null,
    ) : Part

    /**
     * A reasoning block exactly as its provider returned it, replayed to that provider untouched.
     *
     * Both APIs bind a block to the model that wrote it — an encrypted payload on OpenAI, a signed one
     * on Anthropic — and want it back verbatim within the same turn, so nothing here reads into it. A
     * client skips a block another provider wrote.
     */
    data class Reasoning(val provider: LlmProvider, val raw: JsonObject) : Part

    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: JsonObject,
    ) : Part
}

/** What a tool answered to one [Part.ToolCall]. */
data class ToolResult(
    val callId: String,
    val name: String,
    val output: String,
    val isError: Boolean = false,
)

sealed interface Message {

    data class System(val text: String) : Message

    data class User(val parts: List<Part>) : Message {

        constructor(text: String) : this(listOf(Part.Text(text)))

        val text: String
            get() = parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
    }

    data class Assistant(val parts: List<Part>) : Message {

        constructor(text: String) : this(listOf(Part.Text(text)))

        val text: String
            get() = parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }

        val toolCalls: List<Part.ToolCall>
            get() = parts.filterIsInstance<Part.ToolCall>()
    }

    /** The answers to the tool calls of the assistant message before it, all of them in one message. */
    data class ToolResults(val results: List<ToolResult>) : Message {

        init {
            require(results.isNotEmpty()) { "a tool results message carries at least one result" }
        }
    }
}
