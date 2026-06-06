package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
    fun `openai-compatible provider disables parallel tool calls`() {
        // Third-party models (e.g. DeepSeek) garble parallel tool calls; the runtime must force
        // one tool call per turn so the provider never serializes a corrupt parallel batch.
        val runtime =
            resolveLlmRuntime(
                LlmProviderConfig.OpenAiCompatible(
                    baseUrl = "https://example.test/v1",
                    apiKey = "key",
                    model = "deepseek-chat",
                    requestTimeout = 120.seconds
                )
            )

        val params = assertIs<OpenAIChatParams>(runtime.chatParams)
        assertEquals(false, params.parallelToolCalls)
    }
}
