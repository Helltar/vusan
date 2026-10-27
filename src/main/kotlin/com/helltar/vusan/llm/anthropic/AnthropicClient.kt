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
 *   block is bound to the system prompt, the tools and every message before it, and on a model that
 *   takes no tool partway through the agent moves the tool list mid-turn — `loadTools` widens it — so an
 *   account the API holds to that check would otherwise get a 400 where it now gets the stale block dropped.
 * - On a model that takes them ([LlmModel.offersToolsMidConversation]) a deferred tool is declared with
 *   `defer_loading` from the first request and offered by a `tool_addition` reference where the agent
 *   offered it, so the tool list never changes. Measured on 2026-10-24 through OpenRouter: the reference
 *   works on Opus 4.8, Opus 5, Opus 5.5, Sonnet 5.5, Haiku 5.5, Fable 5 and Fable 5.1, and is a 400 on
 *   Opus 4.6, Sonnet 4.6, Opus 4.7 and Sonnet 5; the prefix before the offer was read back whole.
 * - A request whose model may call no tool keeps them defined, with `tool_choice: none`: tool blocks in
 *   the messages are a 400 without them.
 * - Up to four cache breakpoints when the caller asks for caching: the request-level one, which the API
 *   places on the last block and which every iteration of a tool loop reads back; one on the last system
 *   block, the prefix every request of the deployment shares; and one on the end of each stable prefix of
 *   the replayed history, which the next turn reads back — the live turn is replayed in a shorter shape
 *   then, so the automatic one alone would leave it nothing past the system block. The system and history
 *   ones are kept an hour, which outlives the quiet stretches between a chat's messages at twice the write
 *   price instead of 1.25×; the tail stays on the five-minute default, and that order — the longer TTL
 *   first — is the one the API allows.
 * - Unknown content block types are skipped with a warning rather than failing the call.
 *
 * [baseUrl] is another server that speaks this API, OpenRouter's among them. [routesBySession] is for a
 * gateway that picks the upstream per request, as OpenRouter does: the conversation's cache key goes along
 * as `session_id`, which keeps the conversation on the upstream holding its cached prefix.
 */
class AnthropicClient(
    private val http: HttpClient,
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val label: String = "Anthropic",
    private val routesBySession: Boolean = false,
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

        // a tool offered partway through travels under a beta of its own, which a request without one does without
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
                                // withheld from the model until an offer names it, and out of the rendered prompt
                                // until then: what the deferred tools cost together was 93 tokens on 2026-10-24
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
     * offer as a system message of `tool_addition` references where the model takes one, and the ends of
     * the replayed history marked for the cache.
     *
     * A system message has to follow a user turn and be followed by the assistant's, so an offer that a
     * user message came after — a nudge sent once the reply to the offer was dropped as empty — moves
     * behind that message. The history is marked on its last block, at the lifetime the system prompt
     * has: measured on 2026-10-24 (Haiku 5.5 through OpenRouter, a 3.2k-token history), the next turn read
     * 7636 tokens instead of 4428 and wrote 555 instead of 3763. With the system block's and the request's
     * own, these are the four breakpoints a request may carry.
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
        const val API_VERSION = "2026-10-21"

        // lets a request say what to do with a thinking block whose conversation changed under it; harmless
        // on a model that never binds one, which answers with an empty `input_transformations`.
        const val THINKING_BINDING_CONTROLS_BETA = "thinking-binding-controls-2026-08-01"

        // a tool declared `defer_loading` and offered later by a `tool_addition` reference. an inline definition
        // has a newer beta, `inline-tools-2026-09-15`, which OpenRouter's validator refuses (2026-10-24)
        const val MID_CONVERSATION_TOOLS_BETA = "mid-conversation-tool-changes-2026-07-01"

        // for a model whose ceiling nobody declared: what a thinking model needs to answer at all
        private const val DEFAULT_MAX_TOKENS = 16_000

        private val EPHEMERAL = buildJsonObject { put("type", "ephemeral") }
        private val SYSTEM_PREFIX_CACHE = buildJsonObject { put("type", "ephemeral"); put("ttl", "1h") }

        // the history outlives the quiet stretches between a chat's messages for the same reason the system prompt does
        private val HISTORY_CACHE = SYSTEM_PREFIX_CACHE
        private val log = KotlinLogging.logger {}
    }
}
