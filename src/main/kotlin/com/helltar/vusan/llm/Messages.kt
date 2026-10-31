package com.helltar.vusan.llm

import kotlinx.serialization.json.JsonObject

/** One block of a message. */
sealed interface Part {

    /**
     * Text, and for the model's own text what its endpoint said of it, replayed to that endpoint alone: the
     * Responses API's `phase`, which tells a preamble from the answer; without it the model can read its own
     * preamble back as an answer already given.
     */
    data class Text(val text: String, val endpointFields: EndpointFields? = null) : Part

    class Image(
        val bytes: ByteArray,
        val mimeType: String,
        val fileName: String? = null,
    ) : Part

    /**
     * A reasoning block exactly as the endpoint at [source] returned it, replayed to it untouched.
     *
     * Both APIs bind a block to its writer (encrypted on OpenAI, signed on Anthropic) and want it back
     * verbatim within the turn, so nothing reads into it. A client skips blocks other endpoints wrote: what
     * the Codex backend encrypted means nothing to the platform, and one compatible server's extra item is of
     * no known type to the next, which a mid-turn fallback would otherwise send.
     */
    data class Reasoning(val source: String, val raw: JsonObject) : Part

    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: JsonObject,
    ) : Part
}

/** Fields the endpoint at [source] put on a block, handed back to it unchanged and to no other. */
data class EndpointFields(val source: String, val values: JsonObject)

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

        val text: String
            get() = parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }

        constructor(text: String) : this(listOf(Part.Text(text)))
    }

    data class Assistant(val parts: List<Part>) : Message {

        val text: String
            get() = parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }

        val toolCalls: List<Part.ToolCall>
            get() = parts.filterIsInstance<Part.ToolCall>()

        constructor(text: String) : this(listOf(Part.Text(text)))
    }

    /** The answers to all tool calls of the preceding assistant message, in one message. */
    data class ToolResults(val results: List<ToolResult>) : Message {

        init {
            require(results.isNotEmpty()) { "a tool results message carries at least one result" }
        }
    }

    /**
     * Tools the request declared [ToolDefinition.deferred], offered from this point on by [names].
     *
     * Where the model takes tools mid-conversation ([LlmModel.offersToolsMidConversation]) the client says so
     * here and the cached tool list stays as it was; elsewhere a client lists the tools from the first
     * message and drops this, costing the cache from the prompt's start for the rest of the turn.
     */
    data class ToolsOffered(val names: List<String>) : Message {

        init {
            require(names.isNotEmpty()) { "an offer names at least one tool" }
        }
    }
}
