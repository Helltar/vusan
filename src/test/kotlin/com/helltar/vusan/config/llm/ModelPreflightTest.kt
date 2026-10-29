package com.helltar.vusan.config.llm

import com.helltar.vusan.infra.Http
import com.helltar.vusan.llm.ReasoningEffort
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

// a compatible server's list in the plainest shape met: the window under either name, and what the model takes in
private const val COMPATIBLE_MODELS =
    """{"object":"list","data":[
         {"id":"local-vision","object":"model","context_window":131072,"input_modalities":["text","image"]},
         {"id":"local-text","object":"model","context_length":32768,"input_modalities":["text"]}]}"""

// what ollama 0.40 answered on 2026-10-27: a compatible list with ids alone, and its own api beside it. the
// window under model_info is the trained one, which is not what ollama runs the model with
private const val OLLAMA_MODELS = """{"object":"list","data":[{"id":"gemma4:latest","object":"model","created":1791659551,"owned_by":"library"}]}"""

private fun ollamaShow(parameters: String) =
    """{"capabilities":["completion","vision","tools","thinking"],"parameters":"$parameters","model_info":{"gemma4.context_length":131072}}"""

private const val OLLAMA_LOADED = """{"models":[{"name":"gemma4:latest","model":"gemma4:latest","context_length":65536,"runner":"llamacpp"}]}"""

// what openrouter's list of the models a key may use answered on 2026-10-23, trimmed to the fields read
private const val OPENROUTER_MODELS =
    """{"data":[
         {"id":"anthropic/claude-opus-5.5","context_length":1000000,"architecture":{"input_modalities":["text","image","file"]},
          "top_provider":{"context_length":1000000,"max_completion_tokens":128000},"supported_parameters":["reasoning","tools","verbosity"],
          "reasoning":{"mandatory":true,"supported_efforts":["max","xhigh","high","medium","low"],"default_effort":"high"}},
         {"id":"anthropic/claude-haiku-5.5","context_length":1000000,"architecture":{"input_modalities":["text","image","file"]},
          "top_provider":{"context_length":1000000,"max_completion_tokens":128000},"supported_parameters":["reasoning","tools"],
          "reasoning":{"mandatory":false,"supported_efforts":["max","xhigh","high","medium","low","none"],"default_effort":"medium"}},
         {"id":"anthropic/claude-haiku-4.5","context_length":200000,"architecture":{"input_modalities":["text","image"]},
          "top_provider":{"context_length":200000,"max_completion_tokens":64000},"supported_parameters":["reasoning","tools"],"reasoning":{"mandatory":false}},
         {"id":"openai/gpt-5.6-luna","context_length":1050000,"architecture":{"input_modalities":["file","image","text"]},
          "top_provider":{"context_length":1050000,"max_completion_tokens":128000},"supported_parameters":["reasoning","tools"],
          "reasoning":{"mandatory":false,"supported_efforts":["max","xhigh","high","medium","low","none"],"default_effort":"medium"}},
         {"id":"deepseek/deepseek-v4-pro","context_length":1048576,"architecture":{"input_modalities":["text"]},
          "top_provider":{"context_length":1024000,"max_completion_tokens":384000},"supported_parameters":["reasoning","tools"],
          "reasoning":{"mandatory":false,"supported_efforts":["xhigh","high","none"],"default_effort":"high"}},
         {"id":"liquid/lfm-2.5-2.6b:free","context_length":32768,"architecture":{"input_modalities":["text"]},
          "top_provider":{"context_length":32768,"max_completion_tokens":null},"supported_parameters":["max_tokens","temperature"],"reasoning":null}]}"""

// what google's model list answered for two models on 2026-10-29, trimmed to the fields read
private const val GEMINI_MODEL =
    """{"name":"models/gemini-3.5-flash-lite","inputTokenLimit":1048576,"outputTokenLimit":65536,
       "supportedGenerationMethods":["generateContent","countTokens","createCachedContent","batchGenerateContent"],"thinking":true}"""

private const val GOOGLE_EMBEDDING_MODEL =
    """{"name":"models/gemini-embedding-2","inputTokenLimit":8192,"outputTokenLimit":1,"supportedGenerationMethods":["embedContent"]}"""

private const val GOOGLE_PLAIN_MODEL =
    """{"name":"models/gemini-2-flash","inputTokenLimit":1048576,"outputTokenLimit":8192,"supportedGenerationMethods":["generateContent"],"thinking":false}"""

// what xai answered on 2026-10-21, trimmed: the model list for an alias names the model's own id
private const val XAI_REASONING_ALIAS =
    """{"id":"grok-4.20-0309-reasoning","aliases":["grok-4.20"],"context_length":1000000,"object":"model","owned_by":"xai","capabilities":null}"""

private const val XAI_MODEL =
    """{"id":"grok-4.3","aliases":["grok-4.3-latest"],"context_length":1000000,"object":"model","owned_by":"xai",
       "capabilities":{"reasoning_effort":["none","low","medium","high","xhigh"],"default_reasoning_effort":"low"}}"""

private const val XAI_LANGUAGE_MODEL_43 = """{"id":"grok-4.3","input_modalities":["text","image"],"output_modalities":["text"]}"""

class ModelPreflightTest {

    private var asked: String? = null
    private var authorization: Headers? = null

    private val timeout = 120.seconds
    private val askedUrls = mutableListOf<String>()
    private val xaiAsked = mutableListOf<String>()

    private fun http(status: HttpStatusCode, body: String = "") =
        Http.createClient(
            MockEngine { request ->
                asked = request.url.toString()
                authorization = request.headers
                respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
        )

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
            baseUrl = "https://models.test/",
            apiKey = "key",
            model = model,
            reasoningEffort = effort,
            requestTimeout = timeout,
            contextWindowTokens = window,
        )

    @Test
    fun `a compatible server's list says whether the model sees and how much it holds`() = runBlocking {
        val vision = assertIs<LlmProviderConfig.OpenAiCompatible>(compatible("local-vision").preflighted(http(HttpStatusCode.OK, COMPATIBLE_MODELS), codexAuth = null))

        assertEquals("https://models.test/v1/models", asked)
        assertEquals("Bearer key", authorization?.get("Authorization"))
        assertEquals(true, vision.seesImages)
        assertEquals(131_072, vision.contextWindowTokens)
        assertNull(vision.efforts, "no vendor's shape of efforts is read here")

        val text = assertIs<LlmProviderConfig.OpenAiCompatible>(compatible("local-text").preflighted(http(HttpStatusCode.OK, COMPATIBLE_MODELS), codexAuth = null))
        assertEquals(false, text.seesImages)
        assertEquals(32_768, text.contextWindowTokens)
    }

    // which efforts a model takes is the first turn's to say; the list is not asked
    @Test
    fun `a configured window wins, and any effort is left to the first turn`() = runBlocking {
        val checked = compatible("local-vision", effort = ReasoningEffort.MEDIUM, window = 100_000).preflighted(http(HttpStatusCode.OK, COMPATIBLE_MODELS), codexAuth = null)

        assertEquals(100_000, checked.contextWindowTokens)
        assertEquals(ReasoningEffort.MEDIUM, checked.reasoningEffort)
    }

    // a compatible server may serve no list, or answer to an alias it never lists
    @Test
    fun `a server without a list, or one that does not list the model, leaves the config alone`() = runBlocking {
        val config = compatible("local-model")

        assertSame(config, config.preflighted(http(HttpStatusCode.NotFound), codexAuth = null))
        assertSame(config, config.preflighted(http(HttpStatusCode.OK, COMPATIBLE_MODELS), codexAuth = null))
        assertSame(config, config.preflighted(http(HttpStatusCode.OK, "<html>not a list</html>"), codexAuth = null))
    }

    // answers each path from its own body, and a 404 for any path not given one
    private fun routed(vararg bodies: Pair<String, String>) =
        Http.createClient(
            MockEngine { request ->
                askedUrls += request.url.toString()
                val body = bodies.firstOrNull { (path, _) -> request.url.encodedPath == path }?.second

                if (body == null) respond("", HttpStatusCode.NotFound) else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
        )

    private fun ollama() =
        LlmProviderConfig.OpenAiCompatible(baseUrl = "http://localhost:11434", apiKey = "ollama", model = "gemma4:latest", requestTimeout = timeout)

    @Test
    fun `ollama's own api says the model sees, and its modelfile's num_ctx is the window`() = runBlocking {
        val http = routed("/v1/models" to OLLAMA_MODELS, "/api/show" to ollamaShow("""temperature 1\nnum_ctx 32768"""), "/api/ps" to OLLAMA_LOADED)

        val checked = assertIs<LlmProviderConfig.OpenAiCompatible>(ollama().preflighted(http, codexAuth = null))

        assertEquals(true, checked.seesImages)
        assertEquals(32_768, checked.contextWindowTokens)
    }

    @Test
    fun `without a num_ctx the window is the one a loaded model runs with`() = runBlocking {
        val http = routed("/v1/models" to OLLAMA_MODELS, "/api/show" to ollamaShow("temperature 1"), "/api/ps" to OLLAMA_LOADED)

        assertEquals(65_536, ollama().preflighted(http, codexAuth = null).contextWindowTokens)
    }

    // the trained window may be far above the one ollama runs with, which cuts the prompt from the front
    @Test
    fun `a model not loaded and without a num_ctx gets no window, not the one it was trained on`() = runBlocking {
        val http = routed("/v1/models" to OLLAMA_MODELS, "/api/show" to ollamaShow("temperature 1"), "/api/ps" to """{"models":[]}""")

        val checked = assertIs<LlmProviderConfig.OpenAiCompatible>(ollama().preflighted(http, codexAuth = null))

        assertNull(checked.contextWindowTokens)
        assertEquals(true, checked.seesImages)
    }

    private fun openRouter(model: String, effort: ReasoningEffort? = null) =
        LlmProviderConfig.OpenRouter(apiKey = "key", model = model, reasoningEffort = effort, requestTimeout = timeout)

    private suspend fun LlmProviderConfig.OpenRouter.checked() = assertIs<LlmProviderConfig.OpenRouter>(preflighted(http(HttpStatusCode.OK, OPENROUTER_MODELS), codexAuth = null))

    @Test
    fun `openrouter's list fills in the window, the ceiling, the eyes and the efforts`() = runBlocking {
        val checked = openRouter("openai/gpt-5.6-luna", ReasoningEffort.NONE).checked()

        assertEquals("https://openrouter.ai/api/v1/models/user", asked)
        assertEquals("Bearer key", authorization?.get("Authorization"))
        assertEquals(1_050_000, checked.contextWindowTokens)
        assertEquals(128_000, checked.maxOutputTokens)
        assertEquals(true, checked.seesImages)
        assertEquals(ReasoningEffort.entries.toSet(), checked.efforts)
    }

    // the window an upstream serves the model with is what a request can use, not the model's own
    @Test
    fun `the top provider's window wins over the model's, and a model without eyes says so`() = runBlocking {
        val checked = openRouter("deepseek/deepseek-v4-pro").checked()

        assertEquals(1_024_000, checked.contextWindowTokens)
        assertEquals(false, checked.seesImages)
        assertEquals(setOf(ReasoningEffort.NONE, ReasoningEffort.HIGH, ReasoningEffort.XHIGH), checked.efforts)
    }

    // claude turns thinking off through an effort the messages api lacks, so none is not offered for it
    @Test
    fun `a claude model on openrouter takes only claude's efforts, and none stops the startup`() = runBlocking {
        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX), openRouter("anthropic/claude-haiku-5.5").checked().efforts)

        val failure = assertFailsWith<IllegalStateException> { openRouter("anthropic/claude-haiku-5.5", ReasoningEffort.NONE).checked() }
        assertContains(failure.message.orEmpty(), "LLM_REASONING_EFFORT=[none]")
    }

    @Test
    fun `a claude model before 4_6 stops the startup on openrouter`() = runBlocking {
        val failure = assertFailsWith<IllegalStateException> { openRouter("anthropic/claude-haiku-4.5").checked() }

        assertContains(failure.message.orEmpty(), "from 4.6 on")
    }

    @Test
    fun `a routing variant is read as its model`() = runBlocking {
        assertEquals(1_000_000, openRouter("anthropic/claude-opus-5.5:nitro").checked().contextWindowTokens)
    }

    @Test
    fun `a model the key may not use, one that calls no tools, or an effort it does not list stops the startup`() = runBlocking {
        assertContains(assertFailsWith<IllegalStateException> { openRouter("openai/gpt-nope").checked() }.message.orEmpty(), "LLM_MODEL=[openai/gpt-nope]")
        assertContains(assertFailsWith<IllegalStateException> { openRouter("liquid/lfm-2.5-2.6b:free").checked() }.message.orEmpty(), "calls no tools")
        assertContains(assertFailsWith<IllegalStateException> { openRouter("deepseek/deepseek-v4-pro", ReasoningEffort.LOW).checked() }.message.orEmpty(), "none, high, xhigh")
    }

    @Test
    fun `an openrouter list that could not be read leaves the config alone`() = runBlocking {
        val config = openRouter("openai/gpt-5.6-luna")

        assertSame(config, config.preflighted(http(HttpStatusCode.ServiceUnavailable), codexAuth = null))
    }

    @Test
    fun `google's model list gives the window and the ceiling, with the key in its own header`() = runBlocking {
        val config = LlmProviderConfig.Google(apiKey = "key", model = "gemini-3.5-flash-lite", requestTimeout = timeout)

        val checked = assertIs<LlmProviderConfig.Google>(config.preflighted(http(HttpStatusCode.OK, GEMINI_MODEL), codexAuth = null))

        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite", asked)
        assertEquals("key", authorization?.get("x-goog-api-key"))
        assertEquals(1_048_576, checked.contextWindowTokens)
        assertEquals(65_536, checked.maxOutputTokens)
        // the list says the model thinks but not at which levels, and nothing of images
        assertNull(checked.efforts)
        assertNull(checked.seesImages)
    }

    @Test
    fun `a google model that writes no text, or an effort for one that does not think, stops the startup`() = runBlocking {
        val embedding = LlmProviderConfig.Google(apiKey = "key", model = "gemini-embedding-2", requestTimeout = timeout)
        val failure = assertFailsWith<IllegalStateException> { embedding.preflighted(http(HttpStatusCode.OK, GOOGLE_EMBEDDING_MODEL), codexAuth = null) }
        assertContains(failure.message.orEmpty(), "LLM_MODEL=[gemini-embedding-2]")

        val plain = LlmProviderConfig.Google(apiKey = "key", model = "gemini-2-flash", requestTimeout = timeout)
        val unset = assertIs<LlmProviderConfig.Google>(plain.preflighted(http(HttpStatusCode.OK, GOOGLE_PLAIN_MODEL), codexAuth = null))
        assertEquals(emptySet(), unset.efforts)

        val effortFailure =
            assertFailsWith<IllegalStateException> {
                plain.copy(reasoningEffort = ReasoningEffort.LOW).preflighted(http(HttpStatusCode.OK, GOOGLE_PLAIN_MODEL), codexAuth = null)
            }
        assertContains(effortFailure.message.orEmpty(), "LLM_REASONING_EFFORT=[low]")
    }

    @Test
    fun `a model google does not serve stops the startup`() = runBlocking {
        val config = LlmProviderConfig.Google(apiKey = "key", model = "gemini-9-nope", requestTimeout = timeout)

        val failure = assertFailsWith<IllegalStateException> { config.preflighted(http(HttpStatusCode.NotFound), codexAuth = null) }

        assertContains(failure.message.orEmpty(), "LLM_MODEL=[gemini-9-nope]")
    }

    // what deepseek's `GET /models/{id}` answered for flash on 2026-10-29, trimmed to the fields read
    @Test
    fun `deepseek's own list gives the window, the ceiling, the eyes and the levels`() = runBlocking {
        val config = LlmProviderConfig.DeepSeek(apiKey = "key", model = "deepseek-flash", reasoningEffort = ReasoningEffort.NONE, requestTimeout = timeout)
        val flash = DEEPSEEK_MODELS.let { Json.parseToJsonElement(it).jsonObject.getValue("data").jsonArray.first().toString() }

        val checked = assertIs<LlmProviderConfig.DeepSeek>(config.preflighted(http(HttpStatusCode.OK, flash), codexAuth = null))

        assertEquals("https://api.deepseek.com/models/deepseek-flash", asked)
        assertEquals("Bearer key", authorization?.get("Authorization"))
        assertEquals(1_048_576, checked.contextWindowTokens)
        assertEquals(393_216, checked.maxOutputTokens)
        assertEquals(true, checked.seesImages)
        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX), checked.efforts)
    }

    // deepseek would serve `medium` as `high` without a word
    @Test
    fun `an effort deepseek does not list, or a model it does not serve, stops the startup`() = runBlocking {
        val flash = DEEPSEEK_MODELS.let { Json.parseToJsonElement(it).jsonObject.getValue("data").jsonArray.first().toString() }
        val medium = LlmProviderConfig.DeepSeek(apiKey = "key", model = "deepseek-flash", reasoningEffort = ReasoningEffort.MEDIUM, requestTimeout = timeout)

        val failure = assertFailsWith<IllegalStateException> { medium.preflighted(http(HttpStatusCode.OK, flash), codexAuth = null) }
        assertContains(failure.message.orEmpty(), "LLM_REASONING_EFFORT=[medium]")

        val unknown = LlmProviderConfig.DeepSeek(apiKey = "key", model = "deepseek-nope", requestTimeout = timeout)
        val missing = assertFailsWith<IllegalStateException> { unknown.preflighted(http(HttpStatusCode.NotFound), codexAuth = null) }
        assertContains(missing.message.orEmpty(), "LLM_MODEL=[deepseek-nope]")
    }

    private fun xaiHttp(model: String, languageModel: String?) =
        Http.createClient(
            MockEngine { request ->
                val url = request.url.toString()
                xaiAsked += url
                authorization = request.headers
                val body = if ("/language-models/" in url) languageModel else model
                if (body == null) respond("", HttpStatusCode.InternalServerError) else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
        )

    private fun xai(model: String, effort: ReasoningEffort? = null) = LlmProviderConfig.Xai(apiKey = "key", model = model, reasoningEffort = effort, requestTimeout = timeout)

    @Test
    fun `xai's lists fill in the window, the efforts with none among them, and the eyes`() = runBlocking {
        val checked = assertIs<LlmProviderConfig.Xai>(xai("grok-4.3", ReasoningEffort.NONE).preflighted(xaiHttp(XAI_MODEL, XAI_LANGUAGE_MODEL_43), codexAuth = null))

        assertEquals(listOf("https://api.x.ai/v1/models/grok-4.3", "https://api.x.ai/v1/language-models/grok-4.3"), xaiAsked)
        assertEquals("Bearer key", authorization?.get("Authorization"))
        assertEquals(1_000_000, checked.contextWindowTokens)
        assertEquals(true, checked.seesImages)
        assertEquals(ReasoningEffort.entries.toSet() - ReasoningEffort.MAX, checked.efforts)
    }

    // a model without capabilities takes no effort and refuses the field, so asking for one stops the startup
    @Test
    fun `an xai model that takes no effort says so, and an effort set for it stops the startup`() = runBlocking {
        val checked = assertIs<LlmProviderConfig.Xai>(xai("grok-4.20").preflighted(xaiHttp(XAI_REASONING_ALIAS, null), codexAuth = null))

        assertEquals(emptySet(), checked.efforts)
        assertNull(checked.seesImages, "a language-model list that did not answer leaves the eyes to the runtime")

        val failure = assertFailsWith<IllegalStateException> { xai("grok-4.20", ReasoningEffort.LOW).preflighted(xaiHttp(XAI_REASONING_ALIAS, null), codexAuth = null) }
        assertContains(failure.message.orEmpty(), "LLM_REASONING_EFFORT=[low]")
        assertContains(failure.message.orEmpty(), "takes none")
    }

    @Test
    fun `an effort the xai model does not list stops the startup`() = runBlocking {
        val failure = assertFailsWith<IllegalStateException> { xai("grok-4.3", ReasoningEffort.MAX).preflighted(xaiHttp(XAI_MODEL, XAI_LANGUAGE_MODEL_43), codexAuth = null) }

        assertContains(failure.message.orEmpty(), "none, low, medium, high, xhigh")
    }

    @Test
    fun `a model xai does not serve stops the startup`() = runBlocking {
        val failure = assertFailsWith<IllegalStateException> { xai("grok-nope").preflighted(http(HttpStatusCode.NotFound), codexAuth = null) }

        assertContains(failure.message.orEmpty(), "LLM_MODEL=[grok-nope]")
    }
}
