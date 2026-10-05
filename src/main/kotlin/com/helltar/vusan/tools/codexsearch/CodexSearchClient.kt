package com.helltar.vusan.tools.codexsearch

import com.helltar.vusan.config.CODEX_BACKEND_BASE_URL
import com.helltar.vusan.config.CodexAuthStore
import com.helltar.vusan.config.codexRequestHeaders
import com.helltar.vusan.config.collectStreamedResponse
import com.helltar.vusan.config.countUsage
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A page the answer cites, as the search model named it. */
data class CodexSearchSource(val title: String, val url: String)

/** What one hosted search came back with: the written answer and the pages it rests on. */
data class CodexSearchAnswer(val text: String, val sources: List<CodexSearchSource>)

/**
 * Web search on the ChatGPT subscription: one short Responses call in which OpenAI's hosted
 * `web_search` tool is the only tool and is forced, so the model on the other side runs the queries,
 * reads the pages and writes the answer.
 *
 * It is a call of its own rather than a tool declared on the chat turn, because beside ordinary
 * function tools the model reaches for those and leaves the hosted one alone.
 */
class CodexSearchClient(
    private val http: HttpClient,
    private val auth: CodexAuthStore,
    private val model: String,
) {

    suspend fun search(question: String): CodexSearchAnswer {
        require(question.isNotBlank()) { "Question must not be blank" }

        val response =
            http.post("$CODEX_BACKEND_BASE_URL/responses") {
                codexRequestHeaders(auth.credentials()).forEach { (name, value) -> header(name, value) }
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)

                timeout {
                    requestTimeoutMillis = SEARCH_TIMEOUT.inWholeMilliseconds
                    socketTimeoutMillis = SEARCH_TIMEOUT.inWholeMilliseconds
                }

                setBody(requestBody(question).toString())
            }

        auth.limits.observe { response.headers[it] }

        val completed = collectStreamedResponse(response.bodyAsText().lines(), json, CLIENT_NAME)
        countUsage(completed, auth.limits)

        val output = (completed["output"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

        // without a search the text is the model's memory dressed as a lookup, which is the one
        // thing this tool must not hand back as an answer from the web.
        check(output.any { it.string("type") == "web_search_call" }) {
            "The search model answered without searching the web"
        }

        val parts =
            output
                .filter { it.string("type") == "message" }
                .flatMap { (it["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
                .filter { it.string("type") == "output_text" }

        val text = parts.mapNotNull { it.string("text") }.joinToString("\n").withoutTracking().trim()
        check(text.isNotEmpty()) { "The search model returned no answer" }

        val sources =
            parts
                .flatMap { (it["annotations"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
                .filter { it.string("type") == "url_citation" }
                .mapNotNull { citation ->
                    citation.string("url")?.withoutTracking()?.takeIf { it.isNotBlank() }?.let {
                        CodexSearchSource(title = citation.string("title").orEmpty(), url = it)
                    }
                }
                .distinctBy { it.url }

        return CodexSearchAnswer(text, sources)
    }

    // the backend takes a streamed, unstored request and nothing else; a reasoning effort is left
    // out because which ones a model accepts differs per model, and its own default always works.
    private fun requestBody(question: String): JsonObject =
        buildJsonObject {
            put("model", model)
            put("stream", true)
            put("store", false)
            put("instructions", INSTRUCTIONS)

            putJsonArray("input") {
                add(
                    buildJsonObject {
                        put("type", "message")
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "input_text")
                                        put("text", question)
                                    },
                                )
                            },
                        )
                    },
                )
            }

            putJsonArray("tools") { add(buildJsonObject { put("type", HOSTED_TOOL) }) }
            putJsonObject("tool_choice") { put("type", HOSTED_TOOL) }
        }

    private companion object {
        const val CLIENT_NAME = "codex-search"
        const val HOSTED_TOOL = "web_search"

        // a search reads pages before it answers; ten seconds is ordinary and a hard question runs longer
        val SEARCH_TIMEOUT = 3.minutes

        const val INSTRUCTIONS =
            "You are a web search worker. " +
                    "Search the web to answer the question, and answer only from what the pages say. " +
                    "Reply in the language of the question, concisely, with the dates, numbers and names the pages give. " +
                    "Say plainly when the pages do not answer it."

        val json = Json { ignoreUnknownKeys = true }

        // every link the hosted tool hands back carries this marker, in the text and in the citations
        val tracking = Regex("""([?&])utm_source=openai(&?)""")

        fun String.withoutTracking(): String =
            tracking.replace(this) { match -> if (match.groupValues[2].isEmpty()) "" else match.groupValues[1] }

        fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    }
}
