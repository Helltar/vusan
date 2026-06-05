package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class LlmRuntimeTest {

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
