package com.helltar.vusan.llm.openai

import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.EndpointFields
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.Reply
import com.helltar.vusan.llm.StopReason
import com.helltar.vusan.llm.TokenUsage
import com.helltar.vusan.llm.ToolDefinition
import com.helltar.vusan.llm.int
import com.helltar.vusan.llm.postEventStream
import com.helltar.vusan.llm.postJson
import com.helltar.vusan.llm.string
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI's Responses API, and every server that speaks it: the platform itself, the Codex backend,
 * OpenRouter, xAI and any compatible server behind `LLM_BASE_URL`.
 *
 * [baseUrl] is where `/responses` hangs (`https://api.openai.com/v1`); [apiKey] is `null` when the [http]
 * client signs its requests itself, as the Codex one does. [streamed] is for a backend that takes
 * streaming requests only: the call streams and the events are folded back into the one response object
 * the plain API would have returned, so nothing above notices. [statelessReasoning] asks the API not to
 * store the conversation and to hand reasoning back encrypted, which is what lets a tool loop echo it; a
 * third-party server may know neither field. [explicitPromptCaching] states the GPT-5.6 generation's cache
 * options — the platform's own fields, which a third-party server may not know: the implicit mode with a
 * breakpoint on the system prefix, or no caching for a prompt that never repeats. [gatewayPromptCaching]
 * narrows those options to what every upstream behind a gateway takes, for OpenRouter: the breakpoint
 * without a lifetime, and the implicit mode left unnamed. [routesBySession] is for a gateway that picks the
 * upstream per request, as OpenRouter does: the conversation's cache key goes along as `session_id` too,
 * which keeps the conversation on the upstream holding its cached prefix.
 *
 * [onResponse] sees every request and the response it got, for a caller that keeps books on them.
 */
class ResponsesClient(
    private val http: HttpClient,
    baseUrl: String,
    apiKey: String?,
    private val streamed: Boolean = false,
    private val statelessReasoning: Boolean = false,
    private val explicitPromptCaching: Boolean = false,
    private val gatewayPromptCaching: Boolean = false,
    private val routesBySession: Boolean = false,
    private val label: String = "OpenAI",
    private val onResponse: (request: JsonObject, response: JsonObject) -> Unit = { _, _ -> },
) : LlmClient {

    private val headers = bearerHeaders(apiKey)

    private val url = "$baseUrl/responses"

    // what the reasoning this client returns is tagged with, and the only reasoning it replays: the platform,
    // the codex backend and a compatible server all speak this protocol, and none reads another's
    private val reasoningSource = url

    override suspend fun complete(model: LlmModel, request: ChatRequest): Reply {
        val body = responsesRequest(model, request)
        val response = if (streamed) streamResponses(body) else http.postJson(label, url, body, headers)
        onResponse(body, response)

        return parseResponses(response)
    }

    override fun close() = http.close()

    internal fun responsesRequest(model: LlmModel, request: ChatRequest): JsonObject {
        val options = request.options
        // a deferred tool reaches a model that takes it partway through as an `additional_tools` item where it
        // was offered, and the tool list, which the cache reads first, stays as it was
        val offersLater = model.offersToolsMidConversation
        val tools = if (offersLater) request.tools.filterNot { it.deferred } else request.toolsListedUpFront()
        val items = request.messages.map { responsesItems(it, request, offersLater) }

        val body =
            buildJsonObject {
                put("model", model.id)
                put("input", JsonArray(items.flatten()))

                if (tools.isNotEmpty()) {
                    putJsonArray("tools") { tools.forEach { add(responsesTool(it)) } }

                    // both are refused in a request that defines no tools
                    if (!request.mayCallTools) put("tool_choice", "none")
                    options.parallelToolCalls?.let { put("parallel_tool_calls", it) }
                }

                options.reasoningEffort?.let { putJsonObject("reasoning") { put("effort", it.requestValue) } }
                options.verbosity?.let { putJsonObject("text") { put("verbosity", it) } }
                options.serviceTier?.let { put("service_tier", it) }
                options.promptCacheKey?.let { put("prompt_cache_key", it) }
                if (routesBySession) options.promptCacheKey?.let { put("session_id", it) }
                options.maxOutputTokens?.let { put("max_output_tokens", it) }

                if (statelessReasoning) {
                    put("store", false)
                    putJsonArray("include") { add("reasoning.encrypted_content") }
                }

                if (streamed) put("stream", true)
            }

        if (!explicitPromptCaching) return body

        return withPromptCacheOptions(body, options.cachePrompt, gatewayPromptCaching, request.stablePrefixes.map { items.take(it).sumOf(List<JsonObject>::size) })
    }

    private fun responsesTool(tool: ToolDefinition): JsonObject =
        buildJsonObject {
            put("type", "function")
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", tool.parameters)
        }

    private fun responsesItems(message: Message, request: ChatRequest, offersLater: Boolean): List<JsonObject> =
        when (message) {
            is Message.System -> listOf(messageItem("developer", listOf(inputText(message.text))))

            is Message.User ->
                listOf(
                    messageItem(
                        "user",
                        message.parts.mapNotNull { part ->
                            when (part) {
                                is Part.Text -> inputText(part.text)

                                is Part.Image ->
                                    buildJsonObject {
                                        put("type", "input_image")
                                        put("image_url", part.dataUrl())
                                    }

                                else -> null
                            }
                        },
                    ),
                )

            is Message.Assistant -> assistantItems(message)

            is Message.ToolResults ->
                message.results.map { result ->
                    buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", result.callId)
                        put("output", result.output)
                    }
                }

            is Message.ToolsOffered ->
                if (offersLater) {
                    val offered = request.tools.filter { it.name in message.names }

                    listOf(
                        buildJsonObject {
                            put("type", "additional_tools")
                            put("role", "developer")
                            putJsonArray("tools") { offered.forEach { add(responsesTool(it)) } }
                        },
                    )
                } else {
                    emptyList()
                }
        }

    // text parts become one assistant message item; a reasoning item and a function call each stand alone,
    // in the order the model produced them, which is the order the api wants them back in. texts the
    // platform marked differently — a preamble's phase against the answer's — stay separate items, each
    // with its own fields back on it.
    private fun assistantItems(message: Message.Assistant): List<JsonObject> =
        buildList {
            val texts = mutableListOf<JsonObject>()
            var fields: JsonObject? = null

            fun flushTexts() {
                if (texts.isNotEmpty()) add(JsonObject(messageItem("assistant", texts.toList()) + fields.orEmpty()))
                texts.clear()
            }

            message.parts.forEach { part ->
                when (part) {
                    is Part.Text -> {
                        val own = part.endpointFields?.takeIf { it.source == reasoningSource }?.values

                        if (own != fields) {
                            flushTexts()
                            fields = own
                        }

                        texts += buildJsonObject { put("type", "output_text"); put("text", part.text) }
                    }

                    is Part.Reasoning ->
                        if (part.source == reasoningSource) {
                            flushTexts()
                            add(part.raw)
                        }

                    is Part.ToolCall -> {
                        flushTexts()
                        add(
                            buildJsonObject {
                                put("type", "function_call")
                                put("call_id", part.id)
                                put("name", part.name)
                                put("arguments", part.arguments.toString())
                            },
                        )
                    }

                    is Part.Image -> Unit
                }
            }

            flushTexts()
        }

    private fun messageItem(role: String, content: List<JsonObject>): JsonObject =
        buildJsonObject {
            put("type", "message")
            put("role", role)
            put("content", JsonArray(content))
        }

    private fun inputText(text: String): JsonObject = buildJsonObject { put("type", "input_text"); put("text", text) }

    // streams the call and folds the events back into the response object the plain api returns
    private suspend fun streamResponses(body: JsonObject): JsonObject {
        val folder = ResponsesStreamFolder(label)

        http.postEventStream(label, url, body, headers, folder::accept)

        return folder.response()
    }

    private fun parseResponses(response: JsonObject): Reply {
        val items = (response["output"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val parts = items.flatMap(::outputParts)
        val refusals =
            items
                .filter { it.string("type") == "message" }
                .flatMap(::messageTexts)
                .filter { (_, isRefusal) -> isRefusal }
                .map { (text, _) -> text }

        val message = Message.Assistant(parts)
        val incompleteReason = (response["incomplete_details"] as? JsonObject)?.string("reason")

        // a response cut short says so whatever it holds: a call it ends in may be cut too
        val stopReason =
            when {
                incompleteReason == "max_output_tokens" -> StopReason.MAX_TOKENS
                incompleteReason == "content_filter" || refusals.isNotEmpty() -> StopReason.REFUSAL
                message.toolCalls.isNotEmpty() -> StopReason.TOOL_CALLS
                response.string("status") == "completed" -> StopReason.END
                else -> StopReason.OTHER
            }

        val usage = response["usage"] as? JsonObject

        return Reply(
            message = message,
            stopReason = stopReason,
            usage =
                usage?.let {
                    val details = it["input_tokens_details"] as? JsonObject

                    TokenUsage(
                        inputTokens = it.int("input_tokens"),
                        outputTokens = it.int("output_tokens"),
                        cacheReadTokens = details?.int("cached_tokens"),
                        cacheWriteTokens = details?.int("cache_write_tokens"),
                        cacheWriteLongTokens = (details?.get("cache_write_tokens_details") as? JsonObject)?.int("ttl_12h_tokens"),
                    )
                },
            model = response.string("model"),
            refusal = refusals.joinToString(" ").takeIf { it.isNotBlank() } ?: incompleteReason?.takeIf { it == "content_filter" },
        )
    }

    private fun outputParts(item: JsonObject): List<Part> =
        when (val type = item.string("type")) {
            "reasoning" -> listOf(Part.Reasoning(reasoningSource, item))
            "message" -> {
                val fields = item.messageFields()
                messageTexts(item).map { (text, _) -> Part.Text(text, fields) }
            }

            "function_call" ->
                listOf(
                    Part.ToolCall(
                        id = item.string("call_id").orEmpty(),
                        name = item.string("name").orEmpty(),
                        arguments = parseToolArguments(item.string("arguments"), label),
                    ),
                )

            else -> {
                log.warn { "$label: skipping an output item of type=[$type]" }
                emptyList()
            }
        }

    // what the platform says of an output message beside its text, kept for the replay: `phase`, a preamble's
    // `commentary` against the answer's `final_answer`, which the platform asks to have back unchanged
    private fun JsonObject.messageFields(): EndpointFields? =
        this["phase"]
            ?.takeUnless { it is JsonNull }
            ?.let { EndpointFields(reasoningSource, JsonObject(mapOf("phase" to it))) }

    // the text blocks of an output message, each flagged when it is the model declining rather than answering
    private fun messageTexts(item: JsonObject): List<Pair<String, Boolean>> =
        (item["content"] as? JsonArray).orEmpty().mapNotNull { block ->
            val content = block as? JsonObject ?: return@mapNotNull null

            when (val type = content.string("type")) {
                "output_text" -> content.string("text").orEmpty() to false
                "refusal" -> content.string("refusal").orEmpty() to true

                else -> {
                    log.warn { "$label: skipping an output content block of type=[$type]" }
                    null
                }
            }
        }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
