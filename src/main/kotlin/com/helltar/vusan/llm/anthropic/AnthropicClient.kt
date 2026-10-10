package com.helltar.vusan.llm.anthropic

import com.helltar.vusan.llm.string
import com.helltar.vusan.llm.int
import com.helltar.vusan.llm.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.serialization.json.*
import java.util.*

/** The efforts the Messages API takes; `none` and `minimal` are OpenAI's words. */
val ANTHROPIC_EFFORTS: Set<ReasoningEffort> =
    setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX)

/**
 * The Anthropic Messages API.
 *
 * What a request carries, and why, was measured live against Haiku 5.5, Opus 5.5 and Fable 5.1
 * (2026-10-08):
 *
 * - `max_tokens` is the model's whole output ceiling. The API requires the field, a model that thinks
 *   before every answer spends part of it on thinking, and output is billed on what is generated, not
 *   on what was allowed. Every call is streamed and folded back into one message, as the vendor's SDKs
 *   do at a ceiling this size: a stream's socket timeout trips on a stall, where a connection that idles
 *   through a long think is dropped at the API's edge.
 * - `thinking: adaptive` for every model that takes an effort (that generation also stopped thinking
 *   without it on Opus 4.7 and 4.8), with `block_binding: drop_block` under its beta header: a thinking
 *   block is bound to the system prompt, the tools and every message before it, and the agent moves the
 *   tool list mid-turn — `loadTools` widens it — so an account the API holds to that check would
 *   otherwise get a 400 where it now gets the stale block dropped.
 * - A request whose model may call no tool keeps them defined, with `tool_choice: none`: tool blocks in
 *   the messages are a 400 without them.
 * - Two cache breakpoints when the caller asks for caching: the request-level one, which the API places
 *   on the last block and which every iteration of a tool loop reads back, and one on the last system
 *   block, the prefix every request of the deployment shares — the next turn replays its history from
 *   storage in another shape, so the automatic one alone misses then. The system one is kept an hour,
 *   which outlives the quiet stretches between a chat's messages at twice the write price instead of
 *   1.25×; the tail stays on the five-minute default, and that order — the longer TTL first — is the one
 *   the API allows.
 * - Unknown content block types are skipped with a warning rather than failing the call.
 */
class AnthropicClient(
    private val http: HttpClient,
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val label: String = "Anthropic",
) : LlmClient {

    // what the reasoning blocks this client returns are tagged with, and the only ones it replays
    private val reasoningSource = "$baseUrl/v1/messages"

    private val headers =
        mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to API_VERSION,
            "anthropic-beta" to THINKING_BINDING_CONTROLS_BETA,
        )

    override suspend fun complete(model: LlmModel, request: ChatRequest): Reply {
        val folder = MessagesStreamFolder(label)

        http.postEventStream(label, reasoningSource, messagesRequest(model, request), headers, folder::accept)

        return parse(folder.response())
    }

    override fun close() = http.close()

    internal fun messagesRequest(model: LlmModel, request: ChatRequest): JsonObject {
        val options = request.options
        val effort = options.reasoningEffort

        require(effort == null || effort in ANTHROPIC_EFFORTS) { "Anthropic takes no effort of ${effort?.requestValue}" }

        val system = request.messages.filterIsInstance<Message.System>()
        val conversation = request.messages.filterNot { it is Message.System }

        return buildJsonObject {
            put("model", model.id)
            put("max_tokens", options.maxOutputTokens ?: model.maxOutputTokens ?: DEFAULT_MAX_TOKENS)
            put("stream", true)

            if (system.isNotEmpty()) {
                putJsonArray("system") {
                    system.forEachIndexed { index, message ->
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", message.text)
                                if (options.cachePrompt && index == system.lastIndex) put("cache_control", SYSTEM_PREFIX_CACHE)
                            },
                        )
                    }
                }
            }

            put("messages", JsonArray(conversation.map(::conversationMessage)))

            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("input_schema", tool.parameters)
                            },
                        )
                    }
                }

                if (!request.mayCallTools) putJsonObject("tool_choice") { put("type", "none") }
            }

            putJsonObject("thinking") {
                put("type", "adaptive")
                putJsonObject("block_binding") { put("prefix_mismatch_behavior", "drop_block") }
            }

            effort?.let { putJsonObject("output_config") { put("effort", it.requestValue) } }

            if (options.cachePrompt) put("cache_control", EPHEMERAL)
        }
    }

    private fun conversationMessage(message: Message): JsonObject =
        when (message) {
            is Message.System -> error("system messages travel in the system field")

            is Message.User ->
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        message.parts.forEach { part ->
                            when (part) {
                                is Part.Text -> add(textBlock(part.text))

                                is Part.Image ->
                                    add(
                                        buildJsonObject {
                                            put("type", "image")
                                            putJsonObject("source") {
                                                put("type", "base64")
                                                put("media_type", part.mimeType)
                                                put("data", Base64.getEncoder().encodeToString(part.bytes))
                                            }
                                        },
                                    )

                                else -> Unit
                            }
                        }
                    }
                }

            is Message.Assistant ->
                buildJsonObject {
                    put("role", "assistant")
                    putJsonArray("content") {
                        message.parts.forEach { part ->
                            when (part) {
                                // a blank text block is a 400, and a model that answered through a tool often left one
                                is Part.Text -> if (part.text.isNotBlank()) add(textBlock(part.text))
                                is Part.Reasoning -> if (part.source == reasoningSource) add(part.raw)

                                is Part.ToolCall ->
                                    add(
                                        buildJsonObject {
                                            put("type", "tool_use")
                                            put("id", part.id)
                                            put("name", part.name)
                                            put("input", part.arguments)
                                        },
                                    )

                                is Part.Image -> Unit
                            }
                        }
                    }
                }

            is Message.ToolResults ->
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        message.results.forEach { result ->
                            add(
                                buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", result.callId)
                                    put("content", result.output)
                                    if (result.isError) put("is_error", true)
                                },
                            )
                        }
                    }
                }
        }

    private fun textBlock(text: String): JsonObject = buildJsonObject { put("type", "text"); put("text", text) }

    private fun parse(response: JsonObject): Reply {
        val parts =
            (response["content"] as? JsonArray).orEmpty().mapNotNull { element ->
                val block = element as? JsonObject ?: return@mapNotNull null

                when (val type = block.string("type")) {
                    "text" -> Part.Text(block.string("text").orEmpty())
                    "thinking", "redacted_thinking" -> Part.Reasoning(reasoningSource, block)

                    "tool_use" ->
                        Part.ToolCall(
                            id = block.string("id").orEmpty(),
                            name = block.string("name").orEmpty(),
                            arguments = block["input"] as? JsonObject ?: JsonObject(emptyMap()),
                        )

                    else -> {
                        log.warn { "$label: skipping a content block of type=[$type]" }
                        null
                    }
                }
            }

        val message = Message.Assistant(parts)

        val stopReason =
            when (response.string("stop_reason")) {
                "tool_use" -> StopReason.TOOL_CALLS
                "end_turn", "stop_sequence" -> StopReason.END
                "max_tokens" -> StopReason.MAX_TOKENS
                "refusal" -> StopReason.REFUSAL
                else -> if (message.toolCalls.isNotEmpty()) StopReason.TOOL_CALLS else StopReason.OTHER
            }

        // set only on a refusal, and either field may be missing from it
        val refusal =
            (response["stop_details"] as? JsonObject)
                ?.let { listOfNotNull(it.string("category"), it.string("explanation")).joinToString(": ") }
                ?.takeIf { it.isNotEmpty() }

        val usage = response["usage"] as? JsonObject

        return Reply(
            message = message,
            stopReason = stopReason,
            usage =
                usage?.let {
                    TokenUsage(
                        inputTokens = it.int("input_tokens"),
                        outputTokens = it.int("output_tokens"),
                        cacheReadTokens = it.int("cache_read_input_tokens"),
                        cacheWriteTokens = it.int("cache_creation_input_tokens"),
                    )
                },
            model = response.string("model"),
            refusal = refusal,
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val API_VERSION = "2023-06-01"

        // lets a request say what to do with a thinking block whose conversation changed under it; harmless
        // on a model that never binds one, which answers with an empty `input_transformations`.
        const val THINKING_BINDING_CONTROLS_BETA = "thinking-binding-controls-2026-08-01"

        // for a model whose ceiling nobody declared: what a thinking model needs to answer at all
        private const val DEFAULT_MAX_TOKENS = 16_000

        private val EPHEMERAL = buildJsonObject { put("type", "ephemeral") }
        private val SYSTEM_PREFIX_CACHE = buildJsonObject { put("type", "ephemeral"); put("ttl", "1h") }
        private val log = KotlinLogging.logger {}
    }
}


