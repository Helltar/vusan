package com.helltar.vusan.config

import com.helltar.vusan.infra.Http
import com.helltar.vusan.llm.ReasoningEffort
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

private const val ANTHROPIC_MODEL =
    """{"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5","max_input_tokens":1000000,"max_tokens":128000,
       "capabilities":{"effort":{"supported":true,"low":{"supported":true},"medium":{"supported":true},"high":{"supported":true},"xhigh":{"supported":true},"max":{"supported":true}},
                       "thinking":{"supported":true,"types":{"enabled":{"supported":false},"adaptive":{"supported":true},"disabled":{"supported":false}}}}}"""

private const val ANTHROPIC_OLD_MODEL =
    """{"type":"model","id":"claude-haiku-4-5-20251001","display_name":"Claude Haiku 4.5","max_input_tokens":200000,"max_tokens":64000,
       "capabilities":{"effort":{"supported":false},"thinking":{"supported":true,"types":{"enabled":{"supported":true},"adaptive":{"supported":false},"disabled":{"supported":true}}}}}"""

class ModelPreflightTest {

    private var asked: String? = null
    private var authorization: Headers? = null

    private fun http(status: HttpStatusCode, body: String = "") =
        Http.createClient(
            MockEngine { request ->
                asked = request.url.toString()
                authorization = request.headers
                respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
        )

    private val timeout = 120.seconds

    @Test
    fun `a model openai serves passes, and the key goes with the question`() = runBlocking {
        val config = LlmProviderConfig.OpenAi(apiKey = "sk-test", model = "gpt-6-luna", requestTimeout = timeout)

        assertSame(config, config.preflighted(http(HttpStatusCode.OK, """{"id":"gpt-6-luna","object":"model"}"""), codexAuth = null))
        assertEquals("https://api.openai.com/v1/models/gpt-6-luna", asked)
        assertEquals("Bearer sk-test", authorization?.get("Authorization"))
    }

    @Test
    fun `a model the provider does not know stops the startup`() = runBlocking {
        val config = LlmProviderConfig.OpenAi(apiKey = "sk-test", model = "gpt-9-nope", requestTimeout = timeout)

        val failure = assertFailsWith<IllegalStateException> { config.preflighted(http(HttpStatusCode.NotFound), codexAuth = null) }

        assertContains(failure.message.orEmpty(), "gpt-9-nope")
    }

    @Test
    fun `an answer that is neither yes nor no leaves the model alone`() = runBlocking {
        val config = LlmProviderConfig.Anthropic(apiKey = "key", model = "claude-opus-5-5", requestTimeout = timeout)

        val checked = config.preflighted(http(HttpStatusCode.ServiceUnavailable), codexAuth = null)

        assertEquals(config, checked)
        assertNull(assertIs<LlmProviderConfig.Anthropic>(checked).takesEffort)
    }

    @Test
    fun `anthropic's model list fills in the window, the ceiling and what the model takes`() = runBlocking {
        val config = LlmProviderConfig.Anthropic(apiKey = "key", model = "claude-opus-5-5", reasoningEffort = ReasoningEffort.XHIGH, requestTimeout = timeout)

        val checked = assertIs<LlmProviderConfig.Anthropic>(config.preflighted(http(HttpStatusCode.OK, ANTHROPIC_MODEL), codexAuth = null))

        assertEquals("https://api.anthropic.com/v1/models/claude-opus-5-5", asked)
        assertEquals("key", authorization?.get("x-api-key"))
        assertEquals(1_000_000, checked.contextWindowTokens)
        assertEquals(128_000, checked.maxOutputTokens)
        assertEquals(true, checked.takesEffort)
    }

    @Test
    fun `a configured window is kept over the vendor's`() = runBlocking {
        val config = LlmProviderConfig.Anthropic(apiKey = "key", model = "claude-opus-5-5", requestTimeout = timeout, contextWindowTokens = 100_000)

        val checked = assertIs<LlmProviderConfig.Anthropic>(config.preflighted(http(HttpStatusCode.OK, ANTHROPIC_MODEL), codexAuth = null))

        assertEquals(100_000, checked.contextWindowTokens)
    }

    @Test
    fun `a model that takes no effort is marked so, and a configured effort for it stops the startup`() = runBlocking {
        val plain = LlmProviderConfig.Anthropic(apiKey = "key", model = "claude-haiku-4-5-20251001", requestTimeout = timeout)
        val checked = assertIs<LlmProviderConfig.Anthropic>(plain.preflighted(http(HttpStatusCode.OK, ANTHROPIC_OLD_MODEL), codexAuth = null))

        assertEquals(false, checked.takesEffort)
        assertEquals(64_000, checked.maxOutputTokens)

        val withEffort = plain.copy(reasoningEffort = ReasoningEffort.LOW)
        val failure = assertFailsWith<IllegalStateException> { withEffort.preflighted(http(HttpStatusCode.OK, ANTHROPIC_OLD_MODEL), codexAuth = null) }
        assertContains(failure.message.orEmpty(), "low")
    }

    @Test
    fun `a compatible server is not asked at all`() = runBlocking {
        val config = LlmProviderConfig.OpenAiCompatible(baseUrl = "https://example.test", apiKey = "key", model = "any", requestTimeout = timeout)

        assertSame(config, config.preflighted(http(HttpStatusCode.NotFound), codexAuth = null))
        assertNull(asked)
    }
}
