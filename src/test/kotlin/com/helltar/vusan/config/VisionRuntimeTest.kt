package com.helltar.vusan.config

import ai.koog.prompt.llm.LLMProvider
import com.helltar.vusan.infra.Http
import com.helltar.vusan.tools.vision.FakePromptExecutor
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class VisionRuntimeTest {

    private val chatExecutor = FakePromptExecutor()

    @Test
    fun `vision falls back to a chat model that can see images`() {
        val chat = hostedChat(HostedLlmProvider.OPENAI, "gpt-5.4-mini")
        val vision = resolveVisionRuntime(config = null, chat = chat, chatExecutor = chatExecutor, requestTimeout = TIMEOUT)

        assertEquals(chat.model, vision?.model)
        assertSame(chatExecutor, vision?.executor)
    }

    @Test
    fun `vision falls back to the codex chat model`() {
        val chat = codexChat()
        val vision = resolveVisionRuntime(config = null, chat = chat, chatExecutor = chatExecutor, requestTimeout = TIMEOUT)

        assertEquals(chat.model, vision?.model)
        assertSame(chatExecutor, vision?.executor)
    }

    @Test
    fun `vision stays off when the chat model cannot see images`() {
        val chat = hostedChat(HostedLlmProvider.DEEPSEEK, "deepseek-v4-pro")

        assertNull(resolveVisionRuntime(config = null, chat = chat, chatExecutor = chatExecutor, requestTimeout = TIMEOUT))
    }

    @Test
    fun `a vision key gives a blind chat model its own openai model and executor`() {
        val chat = hostedChat(HostedLlmProvider.DEEPSEEK, "deepseek-v4-pro")

        val vision =
            resolveVisionRuntime(
                config = OpenAiVisionConfig(apiKey = "key", model = OpenAiVisionConfig.DEFAULT_MODEL),
                chat = chat,
                chatExecutor = chatExecutor,
                requestTimeout = TIMEOUT
            )

        assertEquals(OpenAiVisionConfig.DEFAULT_MODEL, vision?.model?.id)
        assertEquals(LLMProvider.OpenAI, vision?.model?.provider)
        assertNotSame(chatExecutor, vision?.executor)
    }

    @Test
    fun `an openai-compatible chat model never claims vision on its own`() {
        // the server behind LLM_BASE_URL can serve anything, so image support is only ever taken from the key
        val chat =
            resolveLlmRuntime(
                LlmProviderConfig.OpenAiCompatible(
                    baseUrl = "https://example.test/v1",
                    apiKey = "key",
                    model = "gpt-5.4-mini",
                    requestTimeout = TIMEOUT
                )
            )

        assertNull(resolveVisionRuntime(config = null, chat = chat, chatExecutor = chatExecutor, requestTimeout = TIMEOUT))
    }

    @Test
    fun `an unknown vision model fails at startup`() {
        assertFailsWith<IllegalArgumentException> {
            resolveVisionRuntime(
                config = OpenAiVisionConfig(apiKey = "key", model = "gpt-unknown"),
                chat = hostedChat(HostedLlmProvider.OPENAI, "gpt-5.4-mini"),
                chatExecutor = chatExecutor,
                requestTimeout = TIMEOUT
            )
        }
    }

    private fun hostedChat(provider: HostedLlmProvider, model: String): LlmRuntime =
        resolveLlmRuntime(
            LlmProviderConfig.Hosted(
                provider = provider,
                apiKey = "key",
                model = model,
                requestTimeout = TIMEOUT
            )
        )

    private fun codexChat(): LlmRuntime =
        resolveLlmRuntime(
            LlmProviderConfig.Codex(model = "gpt-5.6-terra", requestTimeout = TIMEOUT),
            codexAuth = CodexAuthStore(Http.createClient(MockEngine { error("no calls expected") }))
        )

    private companion object {
        val TIMEOUT = 120.seconds
    }
}
