package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        val runtime =
            resolveLlmRuntime(
                LlmProviderConfig.Hosted(
                    provider = HostedLlmProvider.ANTHROPIC,
                    apiKey = "key",
                    model = "claude-sonnet-4-5",
                    requestTimeout = 120.seconds
                )
            )

        assertEquals(LLMProvider.Anthropic, runtime.model.provider)
        assertEquals("claude-sonnet-4-5", runtime.model.id)
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

    @Test
    fun `openai-compatible provider passes the reasoning effort to each endpoint`() {
        val completions =
            openAiCompatible(reasoningEffort = ReasoningEffort.NONE).let { assertIs<OpenAIChatParams>(it.chatParams) }

        val responses =
            openAiCompatible(endpoint = OpenAiEndpoint.RESPONSES, reasoningEffort = ReasoningEffort.LOW)
                .let { assertIs<OpenAIResponsesParams>(it.chatParams) }

        assertEquals(ReasoningEffort.NONE, completions.reasoningEffort)
        assertEquals(ReasoningEffort.LOW, responses.reasoning?.effort)
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
    fun `openai-compatible provider declares thinking when a reasoning effort is set`() {
        // the client silently drops the effort for a model without the capability.
        val runtime = openAiCompatible(reasoningEffort = ReasoningEffort.NONE)

        assertTrue(runtime.model.supports(LLMCapability.Thinking))
    }

    private fun openAiCompatible(
        baseUrl: String = "https://example.test",
        endpoint: OpenAiEndpoint = OpenAiEndpoint.COMPLETIONS,
        reasoningEffort: ReasoningEffort? = null
    ): LlmRuntime =
        resolveLlmRuntime(
            LlmProviderConfig.OpenAiCompatible(
                baseUrl = baseUrl,
                apiKey = "key",
                model = "deepseek-chat",
                endpoint = endpoint,
                reasoningEffort = reasoningEffort,
                requestTimeout = 120.seconds
            )
        )
}
