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
 * The Anthropic Messages API. What a request carries was measured live on Haiku 5.5, Opus 5.5 and Fable 5.1
 * (2026-10-08):
 *
 * - `max_tokens` is the model's whole output ceiling: the field is required, thinking spends part of it, and
 *   billing counts what is generated. Every call is streamed and folded into one message, as the SDKs do at
 *   this size: a stream's socket timeout trips on a stall, while an idle connection through a long think is
 *   dropped at the API's edge.
 * - `thinking: adaptive` for every model taking an effort (that generation stopped thinking without it on
 *   Opus 4.7 and 4.8), with `block_binding: drop_block` under its beta: a thinking block is bound to the
 *   system prompt, tools and earlier messages, and `loadTools` widens the tool list mid-turn on models
 *   without mid-conversation tools, so an account held to that check gets the stale block dropped instead of
 *   a 400.
 * - Where [LlmModel.offersToolsMidConversation], a deferred tool is declared with `defer_loading` from the
 *   first request and offered by a `tool_addition` reference, so the tool list never changes. Measured
 *   2026-10-24 via OpenRouter: works on Opus 4.8, 5, 5.5, Sonnet 5.5, Haiku 5.5, Fable 5 and 5.1; a 400 on
 *   Opus 4.6, 4.7, Sonnet 4.6 and 5; the prefix before the offer was read back whole.
 * - A request that may call no tool keeps them defined with `tool_choice: none`: tool blocks in the messages
 *   are a 400 without them.
 * - Up to four cache breakpoints when caching is asked: the request-level one on the last block, read back by
 *   every tool-loop iteration; the last system block, shared by the whole deployment; and the end of each
 *   stable history prefix, read back by the next turn, since the live turn is replayed shorter and the
 *   automatic one alone would leave nothing past the system block. System and history are kept an hour (twice
 *   the write price instead of 1.25×) to outlive the quiet between a chat's messages; the tail stays on five
 *   minutes, and the API requires the longer TTL first.
 * - Unknown content block types are skipped with a warning.
 *
 * [baseUrl] may be another server speaking this API, such as OpenRouter. [routesBySession] is for a gateway
 * that picks the upstream per request: the cache key goes along as `session_id` to keep the conversation on
 * the upstream holding its prefix.
 */
class AnthropicClient(
    private val http: HttpClient,
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val label: String = "Anthropic",
    private val routesBySession: Boolean = false,
) : LlmClient {

    // the source tag on reasoning blocks this client returns, and the only ones it replays
    private val reasoningSource = "$baseUrl/v1/messages"

    private val headers =
        mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to API_VERSION,
            "anthropic-beta" to THINKING_BINDING_CONTROLS_BETA,
        )

    override suspend fun complete(model: LlmModel, request: ChatRequest): Reply {
        val folder = MessagesStreamFolder(label)

        // mid-conversation tools travel under a beta of their own, sent only when needed
        val betas =
            if (model.offersToolsMidConversation && request.tools.any { it.deferred }) "$THINKING_BINDING_CONTROLS_BETA,$MID_CONVERSATION_TOOLS_BETA"
            else THINKING_BINDING_CONTROLS_BETA

        http.postEventStream(label, reasoningSource, messagesRequest(model, request), headers + ("anthropic-beta" to betas), folder::accept)

        return parse(folder.response())
    }

    override fun close() = http.close()

    internal fun messagesRequest(model: LlmModel, request: ChatRequest): JsonObject {
        val options = request.options
        val effort = options.reasoningEffort

        require(effort == null || effort in ANTHROPIC_EFFORTS) { "Anthropic takes no effort of ${effort?.requestValue}" }

        val system = request.messages.filterIsInstance<Message.System>()
        val offersLater = model.offersToolsMidConversation
        val tools = if (offersLater) request.tools else request.toolsListedUpFront()

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

            put("messages", conversationMessages(request, offersLater))

            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("input_schema", tool.parameters)
                                // withheld from the model and the rendered prompt until offered; all deferred
                                // tools together cost 93 tokens (2026-10-24)
                                if (tool.deferred) put("defer_loading", true)
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
            if (routesBySession) options.promptCacheKey?.let { put("session_id", it) }
        }
    }

    /**
     * The conversation as the Messages API takes it: system messages lifted into the system field, a tool
     * offer as a system message of `tool_addition` references, and the ends of replayed history marked for
     * the cache.
     *
     * A system message must follow a user turn and precede the assistant's, so an offer followed by a user
     * message (a nudge after an empty reply was dropped) moves behind it. History is marked on its last block
     * at the system prompt's lifetime: measured 2026-10-24 (Haiku 5.5 via OpenRouter, a 3.2k-token history),
     * the next turn read 7636 tokens instead of 4428 and wrote 555 instead of 3763.
     */
    private fun conversationMessages(request: ChatRequest, offersLater: Boolean): JsonArray {
        val rendered = mutableListOf<JsonObject>()
        // what the stable prefixes end on, by the request's own message count
        val renderedBefore = IntArray(request.messages.size + 1)
        val waiting = mutableListOf<JsonObject>()

        request.messages.forEachIndexed { index, message ->
            renderedBefore[index] = rendered.size

            when (message) {
                is Message.System -> Unit

                is Message.ToolsOffered -> if (offersLater) waiting += toolOffer(message)

                is Message.Assistant -> {
                    rendered += waiting
                    waiting.clear()
                    rendered += conversationMessage(message)
                }

                is Message.User, is Message.ToolResults -> {
                    rendered += conversationMessage(message)
                    rendered += waiting
                    waiting.clear()
                }
            }
        }

        renderedBefore[request.messages.size] = rendered.size
        rendered += waiting

        if (request.options.cachePrompt) {
            request.stablePrefixes.map { renderedBefore[it] - 1 }.distinct().filter { it >= 0 }.forEach { position ->
                rendered[position] = rendered[position].withCacheMarkOnLastBlock()
            }
        }

        return JsonArray(rendered)
    }

    private fun toolOffer(offer: Message.ToolsOffered): JsonObject =
        buildJsonObject {
            put("role", "system")
            putJsonArray("content") {
                offer.names.forEach { name ->
                    add(
                        buildJsonObject {
                            put("type", "tool_addition")
                            putJsonObject("tool") { put("type", "tool_reference"); put("name", name) }
                        },
                    )
                }
            }
        }

    private fun JsonObject.withCacheMarkOnLastBlock(): JsonObject {
        val blocks = this["content"] as? JsonArray ?: return this
        val last = blocks.lastOrNull() as? JsonObject ?: return this

        return JsonObject(this + ("content" to JsonArray(blocks.dropLast(1) + JsonObject(last + ("cache_control" to HISTORY_CACHE)))))
    }

    private fun conversationMessage(message: Message): JsonObject =
        when (message) {
            is Message.System -> error("system messages travel in the system field")
            is Message.ToolsOffered -> error("a tool offer travels as a system message of its own")

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
                                // a blank text block is a 400, and a model answering through a tool often
                                // leaves one
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

        // set only on a refusal, and either field may be missing
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
        const val API_VERSION = "2026-10-21"

        // lets a request say what to do with a thinking block whose conversation changed; harmless on models
        // that never bind one.
        const val THINKING_BINDING_CONTROLS_BETA = "thinking-binding-controls-2026-08-01"

        // `defer_loading` plus a later `tool_addition` reference. the newer inline-definition beta,
        // `inline-tools-2026-09-15`, is refused by OpenRouter's validator (2026-10-24)
        const val MID_CONVERSATION_TOOLS_BETA = "mid-conversation-tool-changes-2026-07-01"

        // for a model with no declared ceiling: what a thinking model needs to answer at all
        private const val DEFAULT_MAX_TOKENS = 16_000

        private val EPHEMERAL = buildJsonObject { put("type", "ephemeral") }
        private val SYSTEM_PREFIX_CACHE = buildJsonObject { put("type", "ephemeral"); put("ttl", "1h") }

        // the history outlives the quiet between a chat's messages, like the system prompt
        private val HISTORY_CACHE = SYSTEM_PREFIX_CACHE
        private val log = KotlinLogging.logger {}
    }
}
