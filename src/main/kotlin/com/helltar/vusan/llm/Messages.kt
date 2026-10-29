package com.helltar.vusan.llm

import kotlinx.serialization.json.JsonObject

/** One block of a message. */
sealed interface Part {

    /**
     * Text, and for the model's own what the endpoint said of it beside the words, replayed with it to
     * that endpoint alone — the Responses API's `phase`, which tells a preamble before tool calls from the
     * answer, and without which the model can read its own preamble back as the answer it already gave.
     */
    data class Text(val text: String, val endpointFields: EndpointFields? = null) : Part

    class Image(
        val bytes: ByteArray,
        val mimeType: String,
        val fileName: String? = null,
    ) : Part

    /**
     * A reasoning block exactly as the endpoint at [source] returned it, replayed to that endpoint
     * untouched.
     *
     * Both APIs bind a block to whoever wrote it — an encrypted payload on OpenAI, a signed one on
     * Anthropic — and want it back verbatim within the same turn, so nothing here reads into it. A client
     * skips a block another endpoint wrote: what the Codex backend encrypted means nothing to the
     * platform, and a field one compatible server added is an item of no known type to the next — which
     * a fallback taking over mid-turn would otherwise send.
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

    /** The answers to the tool calls of the assistant message before it, all of them in one message. */
    data class ToolResults(val results: List<ToolResult>) : Message {

        init {
            require(results.isNotEmpty()) { "a tool results message carries at least one result" }
        }
    }

    /**
     * Tools the request declared [ToolDefinition.deferred], offered to the model from this point of the
     * conversation on, by [names].
     *
     * Where the model takes tools partway through ([LlmModel.offersToolsMidConversation]) its client says
     * so here and the tool list, which the provider caches ahead of everything else, stays what it was;
     * elsewhere a client lists the tools from its first message on and leaves this out, which costs the
     * cache from the start of the prompt for the rest of the turn.
     */
    data class ToolsOffered(val names: List<String>) : Message {

        init {
            require(names.isNotEmpty()) { "an offer names at least one tool" }
        }
    }
}
