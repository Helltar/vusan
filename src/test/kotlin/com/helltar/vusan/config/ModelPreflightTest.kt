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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val ANTHROPIC_MODEL =
    """{"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5","max_input_tokens":1000000,"max_tokens":128000,
       "capabilities":{"effort":{"supported":true,"low":{"supported":true},"medium":{"supported":true},"high":{"supported":true},"xhigh":{"supported":true},"max":{"supported":true}},
                       "image_input":{"supported":true},
                       "thinking":{"supported":true,"types":{"enabled":{"supported":false},"adaptive":{"supported":true},"disabled":{"supported":false}}}}}"""

private const val ANTHROPIC_OLD_MODEL =
    """{"type":"model","id":"claude-haiku-4-5-20251001","display_name":"Claude Haiku 4.5","max_input_tokens":200000,"max_tokens":64000,
       "capabilities":{"effort":{"supported":false},"thinking":{"supported":true,"types":{"enabled":{"supported":true},"adaptive":{"supported":false},"disabled":{"supported":true}}}}}"""

private const val DEEPSEEK_MODELS =
    """{"object":"list","data":[
         {"id":"deepseek-flash","object":"model","context_window":1048576,"max_output_tokens":393216,"input_modalities":["text","image"],
          "effort":{"supported_levels":["low","high","max"],"default_level":"high"}},
         {"id":"deepseek-v4-pro","object":"model","context_window":1048576,"input_modalities":["text"]}]}"""

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
    fun `a model the provider does not know stops the startup, naming the variable it came from`() = runBlocking {
        val config = LlmProviderConfig.OpenAi(apiKey = "sk-test", model = "gpt-9-nope", requestTimeout = timeout, envPrefix = "ADDRESSING")

        val failure = assertFailsWith<IllegalStateException> { config.preflighted(http(HttpStatusCode.NotFound), codexAuth = null) }

        assertContains(failure.message.orEmpty(), "ADDRESSING_MODEL=[gpt-9-nope]")
    }

    @Test
    fun `an answer that is neither yes nor no leaves the model alone`() = runBlocking {
        val config = LlmProviderConfig.Anthropic(apiKey = "key", model = "claude-opus-5-5", requestTimeout = timeout)

        val checked = config.preflighted(http(HttpStatusCode.ServiceUnavailable), codexAuth = null)

        assertEquals(config, checked)
    }

    @Test
    fun `anthropic's model list fills in the window, the ceiling and what the model takes`() = runBlocking {
        val config = LlmProviderConfig.Anthropic(apiKey = "key", model = "claude-opus-5-5", reasoningEffort = ReasoningEffort.XHIGH, requestTimeout = timeout)

        val checked = assertIs<LlmProviderConfig.Anthropic>(config.preflighted(http(HttpStatusCode.OK, ANTHROPIC_MODEL), codexAuth = null))

        assertEquals("https://api.anthropic.com/v1/models/claude-opus-5-5", asked)
        assertEquals("key", authorization?.get("x-api-key"))
        assertEquals(1_000_000, checked.contextWindowTokens)
        assertEquals(128_000, checked.maxOutputTokens)
        assertEquals(true, checked.seesImages)
        assertEquals(ReasoningEffort.entries.toSet() - ReasoningEffort.NONE, checked.efforts)
    }

    @Test
    fun `a configured window is kept over the vendor's`() = runBlocking {
        val config = LlmProviderConfig.Anthropic(apiKey = "key", model = "claude-opus-5-5", requestTimeout = timeout, contextWindowTokens = 100_000)

        val checked = assertIs<LlmProviderConfig.Anthropic>(config.preflighted(http(HttpStatusCode.OK, ANTHROPIC_MODEL), codexAuth = null))

        assertEquals(100_000, checked.contextWindowTokens)
    }

    @Test
    fun `a claude model without adaptive thinking stops the startup`() = runBlocking {
        val config = LlmProviderConfig.Anthropic(apiKey = "key", model = "claude-haiku-4-5-20251001", requestTimeout = timeout)

        val failure = assertFailsWith<IllegalStateException> { config.preflighted(http(HttpStatusCode.OK, ANTHROPIC_OLD_MODEL), codexAuth = null) }

        assertContains(failure.message.orEmpty(), "LLM_MODEL=[claude-haiku-4-5-20251001]")
    }

    private fun compatible(model: String, effort: ReasoningEffort? = null, window: Long? = null) =
        LlmProviderConfig.OpenAiCompatible(
            baseUrl = "https://api.deepseek.com/",
            apiKey = "key",
            model = model,
            reasoningEffort = effort,
            requestTimeout = timeout,
            contextWindowTokens = window,
        )

    // deepseek's own list says flash takes images, so it sees without a vision model of its own
    @Test
    fun `a compatible server's list says whether the model sees and how much it holds`() = runBlocking {
        val flash = assertIs<LlmProviderConfig.OpenAiCompatible>(compatible("deepseek-flash").preflighted(http(HttpStatusCode.OK, DEEPSEEK_MODELS), codexAuth = null))

        assertEquals("https://api.deepseek.com/v1/models", asked)
        assertEquals("Bearer key", authorization?.get("Authorization"))
        assertEquals(true, flash.seesImages)
        assertEquals(1_048_576, flash.contextWindowTokens)
        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX), flash.efforts)

        val pro = assertIs<LlmProviderConfig.OpenAiCompatible>(compatible("deepseek-v4-pro").preflighted(http(HttpStatusCode.OK, DEEPSEEK_MODELS), codexAuth = null))
        assertEquals(false, pro.seesImages)
        assertNull(pro.efforts)
    }

    @Test
    fun `an effort the server's list does not offer stops the startup, and a configured window wins`() = runBlocking {
        val failure =
            assertFailsWith<IllegalStateException> {
                compatible("deepseek-flash", effort = ReasoningEffort.MEDIUM).preflighted(http(HttpStatusCode.OK, DEEPSEEK_MODELS), codexAuth = null)
            }

        assertContains(failure.message.orEmpty(), "LLM_REASONING_EFFORT=[medium]")
        assertContains(failure.message.orEmpty(), "low, high, max")

        val windowed = compatible("deepseek-flash", effort = ReasoningEffort.HIGH, window = 100_000).preflighted(http(HttpStatusCode.OK, DEEPSEEK_MODELS), codexAuth = null)
        assertEquals(100_000, windowed.contextWindowTokens)
    }

    // a compatible server may serve no list, or answer to an alias it never lists
    @Test
    fun `a server without a list, or one that does not list the model, leaves the config alone`() = runBlocking {
        val config = compatible("local-model")

        assertSame(config, config.preflighted(http(HttpStatusCode.NotFound), codexAuth = null))
        assertSame(config, config.preflighted(http(HttpStatusCode.OK, DEEPSEEK_MODELS), codexAuth = null))
        assertSame(config, config.preflighted(http(HttpStatusCode.OK, "<html>not a list</html>"), codexAuth = null))
    }
}
