package com.helltar.vusan.llm.openai

import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmException
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.Reply
import com.helltar.vusan.llm.StopReason
import com.helltar.vusan.llm.TokenUsage
import com.helltar.vusan.llm.int
import com.helltar.vusan.llm.postJson
import com.helltar.vusan.llm.string
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
 * OpenAI's Chat Completions API, and every server that speaks it: DeepSeek, and any compatible server behind
 * `LLM_BASE_URL`.
 *
 * [baseUrl] is where `/chat/completions` hangs. [explicitPromptCaching] states the GPT-5.6 generation's cache
 * options, for OpenAI's own API behind the compatible provider. [echoesReasoningContent] is for a server that
 * thinks aloud in `reasoning_content` and wants it back on every tool call of a turn, as DeepSeek documents:
 * a call another endpoint wrote — the provider a fallback took over from — then carries an empty one, since
 * none at all was a 400. [legacyOutputCeiling] is for a server that reads the output ceiling from the original
 * `max_tokens`: OpenAI renamed it `max_completion_tokens` and refuses the old name on a reasoning model, while
 * DeepSeek ignores the new one (asked for 40 tokens on 2026-10-21, it wrote 3,614).
 */
class ChatCompletionsClient(
    private val http: HttpClient,
    baseUrl: String,
    apiKey: String,
    private val explicitPromptCaching: Boolean = false,
    private val echoesReasoningContent: Boolean = false,
    private val legacyOutputCeiling: Boolean = false,
    private val label: String = "OpenAI",
) : LlmClient {

    private val headers = bearerHeaders(apiKey)

    private val url = "$baseUrl/chat/completions"

    // what the reasoning this client returns is tagged with, and the only reasoning it replays
    private val reasoningSource = url

    override suspend fun complete(model: LlmModel, request: ChatRequest): Reply =
        parseCompletion(http.postJson(label, url, completionsRequest(model, request), headers))

    override fun close() = http.close()

    internal fun completionsRequest(model: LlmModel, request: ChatRequest): JsonObject {
        val options = request.options
        // chat completions has no way to offer a tool partway through, so what was offered is listed up front
        val tools = request.toolsListedUpFront()
        val messages = request.messages.map(::completionMessages)

        val body =
            buildJsonObject {
                put("model", model.id)
                put("messages", JsonArray(messages.flatten()))

                if (tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        tools.forEach { tool ->
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
                options.maxOutputTokens?.let { put(if (legacyOutputCeiling) "max_tokens" else "max_completion_tokens", it) }
            }

        if (!explicitPromptCaching) return body

        return withPromptCacheOptions(body, options.cachePrompt, stablePrefixes = request.stablePrefixes.map { messages.take(it).sumOf(List<JsonObject>::size) })
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

            is Message.ToolsOffered -> emptyList()
        }

    private fun parseCompletion(response: JsonObject): Reply {
        val choice = (response["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val finishReason = choice?.string("finish_reason")

        // deepseek's server cut the answer short for want of capacity: an overload, which the rules downstream
        // repeat or hand to the fallback, not an answer to deliver
        if (finishReason == INSUFFICIENT_SYSTEM_RESOURCE) throw LlmException(label, OVERLOADED_STATUS, "finish_reason $INSUFFICIENT_SYSTEM_RESOURCE")

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
                            arguments = parseToolArguments(function?.string("arguments"), label),
                        ),
                    )
                }
            }

        val assistant = Message.Assistant(parts)

        val stopReason =
            when (finishReason) {
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

    private companion object {
        const val REASONING_CONTENT = "reasoning_content"
        const val INSUFFICIENT_SYSTEM_RESOURCE = "insufficient_system_resource"
        const val OVERLOADED_STATUS = 503
    }
}
