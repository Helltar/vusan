package com.helltar.vusan.llm.google

import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmException
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.Reply
import com.helltar.vusan.llm.StopReason
import com.helltar.vusan.llm.TokenUsage
import com.helltar.vusan.llm.int
import com.helltar.vusan.llm.postJson
import com.helltar.vusan.llm.string
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.*

/**
 * The efforts the Gemini API takes, as `thinking_level`: `none` goes out as `minimal`, its "little to no
 * thinking", since a Gemini 3 model cannot stop thinking altogether. Which of them a model takes is the
 * model's: Flash-Lite takes all four, Gemini 3.8 Flash no `minimal`, Gemma 4 only `minimal` and `high`.
 */
val GOOGLE_EFFORTS: Set<ReasoningEffort> =
    setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)

/**
 * The Gemini API's Interactions endpoint, the vendor's default interface since June 2026 (`generateContent`
 * is its legacy one).
 *
 * Every call is stateless (`store: false`): the conversation goes out whole as a list of steps — `user_input`,
 * `model_output`, `thought`, `function_call`, `function_result` — and nothing is kept on the vendor's side
 * to chain to. System messages travel in `system_instruction`, the one string the API takes for them.
 *
 * A `thought` step carries the signature the model's next request in the turn is checked against: without
 * it a turn's second call is a 400 (measured 2026-10-29 on gemini-3.5-flash-lite). So a thought is kept as
 * a [Part.Reasoning] tagged with this endpoint and replayed verbatim, to this endpoint alone. Gemma 4 puts
 * its thinking into the same step as a summary, out of the answer's text.
 *
 * The cache is implicit: a repeated prefix is read back on its own (21,720 of 21,769 tokens on a repeated
 * request with the full tool list), and there is no breakpoint to place. Unknown step and content types are
 * skipped with a warning rather than failing the call.
 */
class GoogleClient(
    private val http: HttpClient,
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val label: String = "Google",
) : LlmClient {

    private val url = "$baseUrl/v1beta/interactions"

    // what the thoughts this client returns are tagged with, and the only ones it replays
    private val reasoningSource = url

    private val headers = mapOf("x-goog-api-key" to apiKey)

    override suspend fun complete(model: LlmModel, request: ChatRequest): Reply =
        parse(http.postJson(label, url, interactionRequest(model, request), headers))

    override fun close() = http.close()

    internal fun interactionRequest(model: LlmModel, request: ChatRequest): JsonObject {
        val options = request.options
        val effort = options.reasoningEffort

        require(effort == null || effort in GOOGLE_EFFORTS) { "Google takes no effort of ${effort?.requestValue}" }

        val system = request.messages.filterIsInstance<Message.System>()
        val tools = request.toolsListedUpFront()

        return buildJsonObject {
            put("model", model.id)
            put("input", JsonArray(request.messages.flatMap(::steps)))
            put("store", false)

            if (system.isNotEmpty()) put("system_instruction", system.joinToString("\n\n") { it.text })

            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
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
            }

            val config =
                buildJsonObject {
                    effort?.let { put("thinking_level", if (it == ReasoningEffort.NONE) MINIMAL_THINKING else it.requestValue) }
                    options.maxOutputTokens?.let { put("max_output_tokens", it) }
                    if (tools.isNotEmpty() && !request.mayCallTools) put("tool_choice", "none")
                }

            if (config.isNotEmpty()) put("generation_config", config)
        }
    }

    private fun steps(message: Message): List<JsonObject> =
        when (message) {
            // lifted into `system_instruction`, and a tool offer is the tool listed up front
            is Message.System, is Message.ToolsOffered -> emptyList()

            is Message.User ->
                listOf(
                    buildJsonObject {
                        put("type", "user_input")
                        putJsonArray("content") {
                            message.parts.forEach { part ->
                                when (part) {
                                    is Part.Text -> add(textContent(part.text))

                                    is Part.Image ->
                                        add(
                                            buildJsonObject {
                                                put("type", "image")
                                                put("mime_type", part.mimeType)
                                                put("data", Base64.getEncoder().encodeToString(part.bytes))
                                            },
                                        )

                                    else -> Unit
                                }
                            }
                        }
                    },
                )

            is Message.Assistant -> assistantSteps(message)

            is Message.ToolResults ->
                message.results.map { result ->
                    buildJsonObject {
                        put("type", "function_result")
                        put("call_id", result.callId)
                        put("name", result.name)
                        put("result", result.output)
                        if (result.isError) put("is_error", true)
                    }
                }
        }

    // the model's turn in the order it came: its texts gathered into one output step until a thought or a call
    // comes between them, a blank text left out, and another endpoint's reasoning not sent here at all
    private fun assistantSteps(message: Message.Assistant): List<JsonObject> =
        buildList {
            val texts = mutableListOf<JsonObject>()

            fun flushTexts() {
                if (texts.isNotEmpty()) add(buildJsonObject { put("type", "model_output"); put("content", JsonArray(texts.toList())) })
                texts.clear()
            }

            message.parts.forEach { part ->
                when (part) {
                    is Part.Text -> if (part.text.isNotBlank()) texts += textContent(part.text)

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
                                put("id", part.id)
                                put("name", part.name)
                                put("arguments", part.arguments)
                            },
                        )
                    }

                    is Part.Image -> Unit
                }
            }

            flushTexts()
        }

    private fun textContent(text: String): JsonObject = buildJsonObject { put("type", "text"); put("text", text) }

    private fun parse(response: JsonObject): Reply {
        val status = response.string("status")

        // a failed or cancelled interaction still answers 200; what went wrong is in its errors, for the rules
        // downstream to read. no safety block was seen on this endpoint (the model declined in its own words),
        // and other harnesses on it and on generateContent treat a blocked answer as an error too
        if (status == "failed" || status == "cancelled") throw LlmException(label, FAILED_STATUS, (response["errors"] ?: response).toString())

        val parts =
            (response["steps"] as? JsonArray).orEmpty().flatMap { element ->
                val step = element as? JsonObject ?: return@flatMap emptyList()

                when (val type = step.string("type")) {
                    "thought" -> listOf(Part.Reasoning(reasoningSource, step))
                    "model_output" -> outputTexts(step)

                    "function_call" ->
                        listOf(
                            Part.ToolCall(
                                id = step.string("id").orEmpty(),
                                name = step.string("name").orEmpty(),
                                arguments = step["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
                            ),
                        )

                    else -> {
                        log.warn { "$label: skipping a step of type=[$type]" }
                        emptyList()
                    }
                }
            }

        val message = Message.Assistant(parts)

        val stopReason =
            when {
                status == "requires_action" || message.toolCalls.isNotEmpty() -> StopReason.TOOL_CALLS
                status == "completed" -> StopReason.END
                status == "incomplete" -> StopReason.MAX_TOKENS
                else -> StopReason.OTHER
            }

        val usage = response["usage"] as? JsonObject

        return Reply(
            message = message,
            stopReason = stopReason,
            usage =
                usage?.let {
                    TokenUsage(
                        inputTokens = it.int("total_input_tokens"),
                        // thinking is output the model was billed for, as every other vendor counts it
                        outputTokens = listOfNotNull(it.int("total_output_tokens"), it.int("total_thought_tokens")).takeIf { counts -> counts.isNotEmpty() }?.sum(),
                        cacheReadTokens = it.int("total_cached_tokens"),
                    )
                },
            model = response.string("model"),
        )
    }

    private fun outputTexts(step: JsonObject): List<Part> =
        (step["content"] as? JsonArray).orEmpty().mapNotNull { element ->
            val content = element as? JsonObject ?: return@mapNotNull null

            when (val type = content.string("type")) {
                "text" -> Part.Text(content.string("text").orEmpty())

                else -> {
                    log.warn { "$label: skipping output content of type=[$type]" }
                    null
                }
            }
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"

        private const val MINIMAL_THINKING = "minimal"

        // an interaction that failed is answered with a 200 and a status saying so
        private const val FAILED_STATUS = 200

        private val log = KotlinLogging.logger {}
    }
}
