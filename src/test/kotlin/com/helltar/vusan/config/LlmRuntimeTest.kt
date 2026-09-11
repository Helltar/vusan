package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ServiceTier
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LlmRuntimeTest {

    @Test
    fun `resolveOpenAiModel resolves configured model names`() {
        assertEquals(OpenAIModels.Chat.GPT5_4Nano, resolveOpenAiModel("gpt-5.4-nano"))
        assertEquals(OpenAIModels.Chat.GPT5_4Mini, resolveOpenAiModel("GPT-5.4-MINI"))
        assertEquals(OpenAIModels.Chat.GPT4_1, resolveOpenAiModel("gpt_4.1"))
    }

    @Test
    fun `resolveOpenAiModel rejects unknown model names`() {
        assertFailsWith<IllegalArgumentException> {
            resolveOpenAiModel("gpt-unknown")
        }
    }

    @Test
    fun `a responses-only model is not handed chat completions params`() {
        assertIs<OpenAIResponsesParams>(openAiHostedParams(resolveOpenAiModel("gpt-5-pro"), "vusan"))
        assertIs<OpenAIResponsesParams>(openAiHostedParams(resolveOpenAiModel("gpt-5-codex"), "vusan"))
        assertIs<OpenAIChatParams>(openAiHostedParams(resolveOpenAiModel("gpt-5.4-mini"), "vusan"))
    }

    @Test
    fun `the prompt cache key survives either endpoint`() {
        val responses = openAiHostedParams(resolveOpenAiModel("gpt-5-pro"), "vusan-recap")
        val chat = openAiHostedParams(resolveOpenAiModel("gpt-5.4-mini"), "vusan-recap")

        assertEquals("vusan-recap", assertIs<OpenAIResponsesParams>(responses).promptCacheKey)
        assertEquals("vusan-recap", assertIs<OpenAIChatParams>(chat).promptCacheKey)
    }

    // koog refuses the model on its first call, not at startup, so a catalog entry that speaks only one
    // endpoint would leave a deployment looking configured and answering nothing.
    @Test
    fun `every openai model is given params its own endpoint accepts`() {
        val endpointModels =
            OpenAIModels.models.filter {
                it.supports(LLMCapability.OpenAIEndpoint.Completions) ||
                        it.supports(LLMCapability.OpenAIEndpoint.Responses)
            }

        assertTrue(endpointModels.isNotEmpty(), "the catalog declared no endpoint capabilities at all")

        endpointModels.forEach { model ->
            when (openAiHostedParams(model, "vusan")) {
                is OpenAIChatParams ->
                    assertTrue(
                        model.supports(LLMCapability.OpenAIEndpoint.Completions),
                        "${model.id} was given chat params but does not speak completions"
                    )

                is OpenAIResponsesParams ->
                    assertTrue(
                        model.supports(LLMCapability.OpenAIEndpoint.Responses),
                        "${model.id} was given responses params but does not speak responses"
                    )

                else -> Unit
            }
        }
    }

    @Test
    fun `resolveModel matches a native provider catalog case-insensitively`() {
        val model = resolveModel(AnthropicModels, "Anthropic", "CLAUDE-SONNET-4-5")
        assertEquals("claude-sonnet-4-5", model.id)
        assertEquals(LLMProvider.Anthropic, model.provider)
    }

    @Test
    fun `resolveModel rejects unknown native model names`() {
        assertFailsWith<IllegalArgumentException> {
            resolveModel(AnthropicModels, "Anthropic", "claude-unknown")
        }
    }

    @Test
    fun `hosted anthropic provider uses the native client and provider`() {
        val runtime = anthropic()

        assertEquals(LLMProvider.Anthropic, runtime.model.provider)
        assertEquals("claude-sonnet-4-5", runtime.model.id)
    }

    // Anthropic caches nothing implicitly, so without this every step of a turn is billed in full.
    @Test
    fun `anthropic chat prompts ask for caching and the recap does not`() {
        val runtime = anthropic()

        assertEquals(AnthropicCacheControl.Default, assertIs<AnthropicParams>(runtime.chatParams).cacheControl)
        assertNull(assertIs<AnthropicParams>(runtime.compactionParams).cacheControl)
    }

    @Test
    fun `each conversation gets a prompt cache key of its own`() {
        val base = openAiCompatible(baseUrl = "https://api.openai.com").chatParams

        val one = assertIs<OpenAIChatParams>(base.forConversation("telegram:1@-100")).promptCacheKey
        val again = assertIs<OpenAIChatParams>(base.forConversation("telegram:1@-100")).promptCacheKey
        val other = assertIs<OpenAIChatParams>(base.forConversation("telegram:2@-100")).promptCacheKey

        assertEquals(one, again)
        assertTrue(one != other)
        assertTrue(one.orEmpty().startsWith("vusan-"))
    }

    // the key is an OpenAI extension: a server that was not given one must not be handed one now
    @Test
    fun `a conversation key is not invented for a provider without one`() {
        val params = openAiCompatible().chatParams

        assertNull(assertIs<OpenAIChatParams>(params.forConversation("telegram:1@-100")).promptCacheKey)
    }

    @Test
    fun `the responses endpoint scopes its cache key the same way`() {
        val base =
            openAiCompatible(baseUrl = "https://api.openai.com", endpoint = OpenAiEndpoint.RESPONSES).chatParams

        val scoped = assertIs<OpenAIResponsesParams>(base.forConversation("telegram:1@-100"))

        assertTrue(scoped.promptCacheKey.orEmpty().startsWith("vusan-"))
        assertTrue(scoped.promptCacheKey != "vusan")
    }

    @Test
    fun `openai-compatible provider disables parallel tool calls`() {
        // third-party models (e.g. DeepSeek) garble parallel tool calls; the runtime must force
        // one tool call per turn so the provider never serializes a corrupt parallel batch.
        val runtime = openAiCompatible()

        val params = assertIs<OpenAIChatParams>(runtime.chatParams)
        assertEquals(false, params.parallelToolCalls)
        assertTrue(runtime.model.supports(LLMCapability.OpenAIEndpoint.Completions))
        assertFalse(runtime.model.supports(LLMCapability.Thinking))
    }

    @Test
    fun `openai-compatible provider targets the responses endpoint on request`() {
        // koog reads the endpoint off the params type, and refuses params whose endpoint the model
        // does not declare — so both have to move together.
        val runtime = openAiCompatible(endpoint = OpenAiEndpoint.RESPONSES)

        val params = assertIs<OpenAIResponsesParams>(runtime.chatParams)
        assertEquals(false, params.parallelToolCalls)
        assertNull(params.reasoning)
        assertTrue(runtime.model.supports(LLMCapability.OpenAIEndpoint.Responses))
        assertFalse(runtime.model.supports(LLMCapability.OpenAIEndpoint.Completions))
        assertTrue(runtime.model.supports(LLMCapability.Thinking))
    }

    // koog's own effort enum stops at `high`, so a higher effort travels outside it, and only koog's
    // serializer decides whether it lands in the body; so the body is read where it leaves the client.
    @Test
    fun `an effort above high reaches the wire on every endpoint`() = runBlocking {
        val completions = sentOpenAiRequest(openAiCompatible(reasoningEffort = ReasoningEffort.MAX))

        val responses =
            sentOpenAiRequest(
                openAiCompatible(endpoint = OpenAiEndpoint.RESPONSES, reasoningEffort = ReasoningEffort.XHIGH)
            )

        val subscription = sentOpenAiRequest(codex(reasoningEffort = ReasoningEffort.XHIGH))

        assertEquals("max", completions.getValue("reasoning_effort").jsonPrimitive.content)
        assertEquals("xhigh", responses.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals("xhigh", subscription.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun `runtime reports the reasoning effort of either endpoint`() {
        assertEquals("none", openAiCompatible(reasoningEffort = ReasoningEffort.NONE).reasoningEffort)
        assertEquals(
            "high",
            openAiCompatible(endpoint = OpenAiEndpoint.RESPONSES, reasoningEffort = ReasoningEffort.HIGH).reasoningEffort
        )
        assertEquals("max", codex(reasoningEffort = ReasoningEffort.MAX).reasoningEffort)
        assertNull(openAiCompatible().reasoningEffort)
    }

    @Test
    fun `official OpenAI compatible endpoint uses the prompt cache key`() {
        val completions =
            openAiCompatible(baseUrl = "https://API.openai.com/")
                .let { assertIs<OpenAIChatParams>(it.chatParams) }

        val responses =
            openAiCompatible(baseUrl = "https://api.openai.com", endpoint = OpenAiEndpoint.RESPONSES)
                .let { assertIs<OpenAIResponsesParams>(it.chatParams) }

        assertEquals("vusan", completions.promptCacheKey)
        assertEquals("vusan", responses.promptCacheKey)
    }

    @Test
    fun `third-party OpenAI compatible endpoint omits the OpenAI cache key`() {
        val params = openAiCompatible().let { assertIs<OpenAIChatParams>(it.chatParams) }

        assertNull(params.promptCacheKey)
    }

    @Test
    fun `configured context window overrides compatible and native model metadata`() {
        val compatible = openAiCompatible(contextWindowTokens = 32_768)
        val native =
            resolveLlmRuntime(
                LlmProviderConfig.Hosted(
                    provider = HostedLlmProvider.ANTHROPIC,
                    apiKey = "key",
                    model = "claude-sonnet-4-5",
                    requestTimeout = 120.seconds,
                    contextWindowTokens = 65_536
                )
            )

        assertEquals(32_768L, compatible.model.contextLength)
        assertEquals(65_536L, native.model.contextLength)
    }

    @Test
    fun `codex provider targets the responses endpoint with reasoning enabled`() {
        val runtime = codex()

        assertEquals("gpt-5.6-terra", runtime.model.id)
        assertEquals(LLMProvider.OpenAI, runtime.model.provider)
        assertTrue(runtime.model.supports(LLMCapability.OpenAIEndpoint.Responses))
        assertTrue(runtime.model.supports(LLMCapability.Thinking))
        assertTrue(runtime.model.supports(LLMCapability.Tools))
        assertTrue(runtime.model.supports(LLMCapability.Vision.Image))
        assertIs<OpenAIResponsesParams>(runtime.chatParams)
    }

    @Test
    fun `codex chat and compaction prompts use separate cache keys`() {
        val runtime = codex()

        val chat = assertIs<OpenAIResponsesParams>(runtime.chatParams)
        val compaction = assertIs<OpenAIResponsesParams>(runtime.compactionParams)

        assertNotNull(chat.promptCacheKey)
        assertTrue(chat.promptCacheKey != compaction.promptCacheKey)
        assertFalse(chat.parallelToolCalls == true)
        assertEquals(listOf(OpenAIInclude.REASONING_ENCRYPTED_CONTENT), chat.include)
        assertEquals(listOf(OpenAIInclude.REASONING_ENCRYPTED_CONTENT), compaction.include)
    }

    @Test
    fun `codex model omits vision when the catalog marks it text only`() {
        val runtime = codex(supportsVision = false)

        assertFalse(runtime.model.supports(LLMCapability.Vision.Image))
    }

    @Test
    fun `codex provider requires an auth store`() {
        assertFailsWith<IllegalArgumentException> {
            resolveLlmRuntime(
                LlmProviderConfig.Codex(model = "gpt-5.6-terra", requestTimeout = 120.seconds),
                codexAuth = null
            )
        }
    }

    @Test
    fun `codex provider carries the configured context window`() {
        assertEquals(400_000L, codex(contextWindowTokens = 400_000).model.contextLength)
    }

    @Test
    fun `codex asks for no serving tier by default`() {
        assertNull(assertIs<OpenAIResponsesParams>(codex().chatParams).serviceTier)
        assertEquals("model=gpt-5.6-terra", codexRoutingHint("gpt-5.6-terra", serviceTier = null))
    }

    @Test
    fun `a configured serving tier reaches both the request params and the routing hint`() {
        val runtime = codex(serviceTier = ServiceTier.PRIORITY)

        assertEquals(ServiceTier.PRIORITY, assertIs<OpenAIResponsesParams>(runtime.chatParams).serviceTier)
        // a history recap is billed against the same allowance, so it travels on the same tier
        assertEquals(ServiceTier.PRIORITY, assertIs<OpenAIResponsesParams>(runtime.compactionParams).serviceTier)
        assertEquals(
            "model=gpt-5.6-terra;tier=priority",
            codexRoutingHint("gpt-5.6-terra", ServiceTier.PRIORITY)
        )
    }

    private fun anthropic(): LlmRuntime =
        resolveLlmRuntime(
            LlmProviderConfig.Hosted(
                provider = HostedLlmProvider.ANTHROPIC,
                apiKey = "key",
                model = "claude-sonnet-4-5",
                requestTimeout = 120.seconds
            )
        )

    private fun codex(
        contextWindowTokens: Long? = null,
        supportsVision: Boolean = true,
        reasoningEffort: ReasoningEffort? = null,
        serviceTier: ServiceTier? = null
    ): LlmRuntime =
        resolveLlmRuntime(
            LlmProviderConfig.Codex(
                model = "gpt-5.6-terra",
                reasoningEffort = reasoningEffort,
                requestTimeout = 120.seconds,
                contextWindowTokens = contextWindowTokens,
                supportsVision = supportsVision,
                serviceTier = serviceTier
            ),
            codexAuth = CodexAuthStore(Http.createClient(MockEngine { error("no calls expected") }))
        )

    private fun openAiCompatible(
        baseUrl: String = "https://example.test",
        endpoint: OpenAiEndpoint = OpenAiEndpoint.COMPLETIONS,
        reasoningEffort: ReasoningEffort? = null,
        contextWindowTokens: Long? = null
    ): LlmRuntime =
        resolveLlmRuntime(
            LlmProviderConfig.OpenAiCompatible(
                baseUrl = baseUrl,
                apiKey = "key",
                model = "deepseek-chat",
                endpoint = endpoint,
                reasoningEffort = reasoningEffort,
                requestTimeout = 120.seconds,
                contextWindowTokens = contextWindowTokens
            )
        )
}
