package com.helltar.vusan.llm.openai

import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.Reply
import com.helltar.vusan.llm.StopReason
import com.helltar.vusan.llm.TokenUsage
import com.helltar.vusan.llm.llmJson
import com.helltar.vusan.llm.postEventStream
import com.helltar.vusan.llm.postJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** Which OpenAI HTTP API a request goes to: `/chat/completions` or `/responses`. */
enum class OpenAiEndpoint {
    COMPLETIONS,
    RESPONSES
}

/**
 * The OpenAI API, and everything that speaks it: the platform itself, the Codex backend, and any
 * third-party server behind `LLM_BASE_URL`.
 *
 * [baseUrl] is where the two endpoints hang (`https://api.openai.com/v1`); [apiKey] is `null` when the
 * [http] client signs its requests itself, as the Codex one does. [streamed] is for a backend that takes
 * streaming requests only: the call streams and the events are folded back into the one response object
 * the plain API would have returned, so nothing above notices. [statelessReasoning] asks the Responses
 * API not to store the conversation and to hand reasoning back encrypted, which is what lets a tool
 * loop echo it; a third-party server may know neither field. [explicitPromptCaching] states the GPT-5.6
 * generation's cache options — the platform's own fields, which a third-party server may not know: the
 * implicit mode with a breakpoint on the system prefix, or no caching for a prompt that never repeats.
 * [echoesReasoningContent] is for a Chat Completions server that thinks aloud in `reasoning_content` and
 * wants it back on every tool call of a turn, as DeepSeek does: a call another endpoint wrote — the
 * provider a fallback took over from — then carries an empty one, since none at all is a 400.
 *
 * [onResponse] sees every request and the response it got, for a caller that keeps books on them.
 */
class OpenAiClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String?,
    private val endpoint: OpenAiEndpoint,
    private val streamed: Boolean = false,
    private val statelessReasoning: Boolean = false,
    private val explicitPromptCaching: Boolean = false,
    private val echoesReasoningContent: Boolean = false,
    private val label: String = "OpenAI",
    private val onResponse: (request: JsonObject, response: JsonObject) -> Unit = { _, _ -> },
) : LlmClient {

    private val headers: Map<String, String> = apiKey?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()

    private val url =
        when (endpoint) {
            OpenAiEndpoint.RESPONSES -> "$baseUrl/responses"
            OpenAiEndpoint.COMPLETIONS -> "$baseUrl/chat/completions"
        }

    // what the reasoning this client returns is tagged with, and the only reasoning it replays: the platform,
    // the codex backend and a compatible server all speak this protocol, and none reads another's
    private val reasoningSource = url

    override suspend fun complete(model: LlmModel, request: ChatRequest): Reply =
        when (endpoint) {
            OpenAiEndpoint.RESPONSES -> {
                val body = responsesRequest(model, request)
                val response = if (streamed) streamResponses(body) else http.postJson(label, url, body, headers)
                onResponse(body, response)
                parseResponses(response)
            }

            OpenAiEndpoint.COMPLETIONS -> {
                val body = completionsRequest(model, request)
                val response = http.postJson(label, url, body, headers)
                onResponse(body, response)
                parseCompletion(response)
            }
        }

    override fun close() = http.close()

    // --- responses api ---

    internal fun responsesRequest(model: LlmModel, request: ChatRequest): JsonObject {
        val options = request.options

        val body =
            buildJsonObject {
                put("model", model.id)
                put("input", JsonArray(request.messages.flatMap(::responsesItems)))

                if (request.tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        request.tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("type", "function")
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.parameters)
                                },
                            )
                        }
                    }

                    // both are refused in a request that defines no tools
                    if (!request.mayCallTools) put("tool_choice", "none")
                    options.parallelToolCalls?.let { put("parallel_tool_calls", it) }
                }

                options.reasoningEffort?.let { putJsonObject("reasoning") { put("effort", it.requestValue) } }
                options.verbosity?.let { putJsonObject("text") { put("verbosity", it) } }
                options.serviceTier?.let { put("service_tier", it) }
                options.promptCacheKey?.let { put("prompt_cache_key", it) }
                options.maxOutputTokens?.let { put("max_output_tokens", it) }

                // a model that does not reason refuses the encrypted reasoning it would never write
                if (statelessReasoning) {
                    put("store", false)
                    if (model.takesEffort) putJsonArray("include") { add("reasoning.encrypted_content") }
                }

                if (streamed) put("stream", true)
            }

        return if (explicitPromptCaching) withPromptCacheOptions(body, options.cachePrompt) else body
    }

    private fun responsesItems(message: Message): List<JsonObject> =
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
                                        put("detail", "auto")
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
        }

    // text parts become one assistant message item; a reasoning item and a function call each stand alone,
    // in the order the model produced them, which is the order the api wants them back in.
    private fun assistantItems(message: Message.Assistant): List<JsonObject> =
        buildList {
            val texts = mutableListOf<JsonObject>()

            fun flushTexts() {
                if (texts.isNotEmpty()) add(messageItem("assistant", texts.toList()))
                texts.clear()
            }

            message.parts.forEach { part ->
                when (part) {
                    is Part.Text -> texts += buildJsonObject { put("type", "output_text"); put("text", part.text) }

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
                    )
                },
            model = response.string("model"),
            refusal = refusals.joinToString(" ").takeIf { it.isNotBlank() } ?: incompleteReason?.takeIf { it == "content_filter" },
        )
    }

    private fun outputParts(item: JsonObject): List<Part> =
        when (val type = item.string("type")) {
            "reasoning" -> listOf(Part.Reasoning(reasoningSource, item))
            "message" -> messageTexts(item).map { (text, _) -> Part.Text(text) }

            "function_call" ->
                listOf(
                    Part.ToolCall(
                        id = item.string("call_id").orEmpty(),
                        name = item.string("name").orEmpty(),
                        arguments = parseArguments(item.string("arguments")),
                    ),
                )

            else -> {
                log.warn { "$label: skipping an output item of type=[$type]" }
                emptyList()
            }
        }

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

    // --- chat completions api ---

    internal fun completionsRequest(model: LlmModel, request: ChatRequest): JsonObject {
        val options = request.options

        val body =
            buildJsonObject {
                put("model", model.id)
                put("messages", JsonArray(request.messages.flatMap(::completionMessages)))

                if (request.tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        request.tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", tool.name)
                                        put("description", tool.description)
                                        put("parameters", tool.parameters)
                                    }
                                },
                            )
                        }
                    }

                    if (!request.mayCallTools) put("tool_choice", "none")
                    options.parallelToolCalls?.let { put("parallel_tool_calls", it) }
                }

                options.reasoningEffort?.let { put("reasoning_effort", it.requestValue) }
                options.serviceTier?.let { put("service_tier", it) }
                options.promptCacheKey?.let { put("prompt_cache_key", it) }
                options.maxOutputTokens?.let { put("max_completion_tokens", it) }
            }

        return if (explicitPromptCaching) withPromptCacheOptions(body, options.cachePrompt) else body
    }

    private fun completionMessages(message: Message): List<JsonObject> =
        when (message) {
            is Message.System -> listOf(buildJsonObject { put("role", "system"); put("content", message.text) })

            is Message.User ->
                listOf(
                    buildJsonObject {
                        put("role", "user")

                        if (message.parts.all { it is Part.Text }) {
                            put("content", message.text)
                        } else {
                            putJsonArray("content") {
                                message.parts.forEach { part ->
                                    when (part) {
                                        is Part.Text -> add(buildJsonObject { put("type", "text"); put("text", part.text) })

                                        is Part.Image ->
                                            add(
                                                buildJsonObject {
                                                    put("type", "image_url")
                                                    putJsonObject("image_url") { put("url", part.dataUrl()) }
                                                },
                                            )

                                        else -> Unit
                                    }
                                }
                            }
                        }
                    },
                )

            is Message.Assistant ->
                listOf(
                    buildJsonObject {
                        put("role", "assistant")
                        put("content", message.text)

                        // a server that thinks between tool calls wants its thoughts back with the call
                        val reasoning =
                            message.parts.filterIsInstance<Part.Reasoning>()
                                .firstOrNull { it.source == reasoningSource }
                                ?.raw?.get(REASONING_CONTENT)

                        when {
                            reasoning != null -> put(REASONING_CONTENT, reasoning)
                            echoesReasoningContent && message.toolCalls.isNotEmpty() -> put(REASONING_CONTENT, "")
                        }

                        if (message.toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                message.toolCalls.forEach { call ->
                                    add(
                                        buildJsonObject {
                                            put("id", call.id)
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", call.name)
                                                put("arguments", call.arguments.toString())
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )

            is Message.ToolResults ->
                message.results.map { result ->
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", result.callId)
                        put("content", result.output)
                    }
                }
        }

    private fun parseCompletion(response: JsonObject): Reply {
        val choice = (response["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject

        val parts =
            buildList {
                message?.get(REASONING_CONTENT)?.takeIf { it !is JsonNull }?.let { reasoning ->
                    add(Part.Reasoning(reasoningSource, buildJsonObject { put(REASONING_CONTENT, reasoning) }))
                }

                message?.string("content")?.takeIf { it.isNotEmpty() }?.let { add(Part.Text(it)) }

                (message?.get("tool_calls") as? JsonArray).orEmpty().forEach { element ->
                    val call = element as? JsonObject ?: return@forEach
                    val function = call["function"] as? JsonObject

                    add(
                        Part.ToolCall(
                            id = call.string("id").orEmpty(),
                            name = function?.string("name").orEmpty(),
                            arguments = parseArguments(function?.string("arguments")),
                        ),
                    )
                }
            }

        val assistant = Message.Assistant(parts)

        val stopReason =
            when (choice?.string("finish_reason")) {
                "tool_calls" -> StopReason.TOOL_CALLS
                "length" -> StopReason.MAX_TOKENS
                "content_filter" -> StopReason.REFUSAL
                "stop" -> if (assistant.toolCalls.isNotEmpty()) StopReason.TOOL_CALLS else StopReason.END
                else -> if (assistant.toolCalls.isNotEmpty()) StopReason.TOOL_CALLS else StopReason.OTHER
            }

        val usage = response["usage"] as? JsonObject

        return Reply(
            message = assistant,
            stopReason = stopReason,
            usage =
                usage?.let {
                    TokenUsage(
                        inputTokens = it.int("prompt_tokens"),
                        outputTokens = it.int("completion_tokens"),
                        cacheReadTokens = (it["prompt_tokens_details"] as? JsonObject)?.int("cached_tokens"),
                    )
                },
            model = response.string("model"),
        )
    }

    // the arguments arrive as a json string; a model that garbles it still gets its call answered, with the
    // empty-args guard in the agent telling it what was missing.
    private fun parseArguments(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return JsonObject(emptyMap())

        return runCatching { llmJson.parseToJsonElement(raw).jsonObject }
            .getOrElse {
                log.warn { "$label: a tool call's arguments were not a json object: ${raw.take(ARGS_PREVIEW_CHARS)}" }
                JsonObject(emptyMap())
            }
    }

    private companion object {
        const val REASONING_CONTENT = "reasoning_content"
        const val ARGS_PREVIEW_CHARS = 200

        val log = KotlinLogging.logger {}
    }
}

private fun Part.Image.dataUrl(): String = "data:$mimeType;base64,${Base64.getEncoder().encodeToString(bytes)}"

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
