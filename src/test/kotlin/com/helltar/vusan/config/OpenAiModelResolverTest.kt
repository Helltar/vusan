package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAiModelResolverTest {

    @Test
    fun `resolves configured model names`() {
        assertEquals(OpenAIModels.Chat.GPT5_4Nano, OpenAiModelResolver.resolve("gpt-5.4-nano"))
        assertEquals(OpenAIModels.Chat.GPT5_4Mini, OpenAiModelResolver.resolve("GPT-5.4-MINI"))
        assertEquals(OpenAIModels.Chat.GPT4_1, OpenAiModelResolver.resolve("gpt_4.1"))
    }

    @Test
    fun `rejects unknown model names`() {
        assertFailsWith<IllegalArgumentException> {
            OpenAiModelResolver.resolve("gpt-unknown")
        }
    }
}
