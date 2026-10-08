package com.helltar.vusan.config

import com.helltar.vusan.infra.Http
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.openai.OpenAiEndpoint
import com.helltar.vusan.llm.codex.CodexAuthStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AddressingRuntimeTest {

    private val asked = mutableListOf<String>()

    // a platform that refuses `none` to the models named, as gpt-6.1-sol and gpt-6-astra do
    private fun platform(refusingNone: Set<String>) =
        Http.createClient(
            MockEngine { request ->
                val body = request.body.toByteArray().decodeToString()
                val model = Regex(""""model":"([^"]+)"""").find(body)?.groupValues?.get(1).orEmpty()
                asked += body

                when {
                    model in refusingNone && "\"effort\":\"none\"" in body ->
                        respond("""{"error":{"message":"Unsupported value: 'none'","param":"reasoning.effort","code":"unsupported_value"}}""", HttpStatusCode.BadRequest, JSON)

                    // a backend that streams, as codex does, answers with events
                    "\"stream\":true" in body -> respond(OK_STREAM, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()))
                    else -> respond(OK_REPLY, HttpStatusCode.OK, JSON)
                }
            },
        )

    private fun effortOf(provider: LlmProviderConfig, codexAuth: CodexAuthStore? = null, refusingNone: Set<String> = emptySet()): ReasoningEffort? =
        runBlocking { resolveAddressingRuntime(AddressingConfig(provider, names = emptyList()), codexAuth, platform(refusingNone)).options.reasoningEffort }

    private fun openAi(model: String, effort: ReasoningEffort? = null) =
        LlmProviderConfig.OpenAi(apiKey = "key", model = model, reasoningEffort = effort, requestTimeout = TIMEOUT)

    private fun anthropic(model: String) = LlmProviderConfig.Anthropic(apiKey = "key", model = model, requestTimeout = TIMEOUT)

    // openai's models disagree on the floor and nothing lists it, so the model is asked once at startup
    @Test
    fun `an openai model is asked whether it takes none, and gets low when it refuses`() {
        assertEquals(ReasoningEffort.NONE, effortOf(openAi("gpt-6-luna"), refusingNone = setOf("gpt-6.1-sol")))
        assertEquals(ReasoningEffort.LOW, effortOf(openAi("gpt-6.1-sol"), refusingNone = setOf("gpt-6.1-sol")))

        asked.clear()
        assertNull(effortOf(openAi("gpt-4.1-mini")), "a model that does not reason is sent no effort")
        assertTrue(asked.isEmpty(), "and is not asked")
    }

    // what a vendor listed at startup — anthropic's model list, the plan's catalog, deepseek's list — is
    // taken without asking; a model nobody listed is asked, whichever api it is on
    @Test
    fun `a verdict gets the least reasoning its vendor lists, and asks where nothing is listed`() {
        val listed = setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)

        assertEquals(ReasoningEffort.LOW, effortOf(anthropic("claude-haiku-5-5").copy(efforts = listed)))
        assertNull(effortOf(anthropic("claude-haiku-4-5-20251001")), "a dated claude model takes no effort")
        assertTrue(asked.isEmpty(), "a listed floor is not asked about")

        val compatible =
            LlmProviderConfig.OpenAiCompatible(
                baseUrl = "https://api.deepseek.com",
                apiKey = "key",
                model = "deepseek-flash",
                endpoint = OpenAiEndpoint.COMPLETIONS,
                requestTimeout = TIMEOUT,
            )

        assertEquals(ReasoningEffort.LOW, effortOf(compatible.copy(efforts = listed)))
        assertEquals(ReasoningEffort.NONE, effortOf(compatible), "a server that lists no efforts is asked")

        val auth = CodexAuthStore(Http.createClient(MockEngine { error("no refresh expected") }))

        assertEquals(ReasoningEffort.LOW, effortOf(LlmProviderConfig.Codex(model = "gpt-5.6-luna", efforts = listed, requestTimeout = TIMEOUT), auth))
        assertEquals(ReasoningEffort.NONE, effortOf(LlmProviderConfig.Codex(model = "gpt-5.6-luna", requestTimeout = TIMEOUT), auth), "a model the catalog did not list is asked")
        assertNull(effortOf(LlmProviderConfig.Codex(model = "gpt-5.6-luna", efforts = emptySet(), requestTimeout = TIMEOUT), auth), "a catalog that lists no choice leaves the default")
    }

    @Test
    fun `an effort the deployment set wins, and nothing is asked`() {
        assertEquals(ReasoningEffort.HIGH, effortOf(openAi("gpt-5.6-luna", effort = ReasoningEffort.HIGH)))
        assertTrue(asked.isEmpty())
    }

    private companion object {
        val TIMEOUT = 30.seconds
        val JSON = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

        const val OK_REPLY =
            """{"id":"r","object":"response","status":"completed","model":"m","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]}],"usage":{"input_tokens":1,"output_tokens":1}}"""

        val OK_STREAM = "data: {\"type\":\"response.completed\",\"response\":$OK_REPLY}\n\n"
    }
}
