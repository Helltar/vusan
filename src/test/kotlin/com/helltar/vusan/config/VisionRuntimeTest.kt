package com.helltar.vusan.config

import com.helltar.vusan.infra.Http
import com.helltar.vusan.llm.FakeLlmClient
import com.helltar.vusan.llm.LlmProvider
import com.helltar.vusan.llm.openai.OpenAiEndpoint
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class VisionRuntimeTest {

    @Test
    fun `vision falls back to a chat model that can see images`() {
        val chat = openAiChat("gpt-5.4-mini")
        val vision = resolveVisionRuntime(config = null, chat = chat)

        assertEquals(chat.model, vision?.model)
        assertSame(chat.client, vision?.client)
        assertEquals(false, vision?.ownClient)
    }

    @Test
    fun `vision falls back to the codex chat model`() {
        val chat = codexChat()
        val vision = resolveVisionRuntime(config = null, chat = chat)

        assertEquals(chat.model, vision?.model)
        assertSame(chat.client, vision?.client)
    }

    @Test
    fun `vision stays off when the codex catalog marks the chat model text only`() {
        assertNull(resolveVisionRuntime(config = null, chat = codexChat(supportsVision = false)))
    }

    // the server behind LLM_BASE_URL can serve anything, so a compatible chat model never claims to see
    @Test
    fun `an openai-compatible chat model never claims vision on its own`() {
        assertNull(resolveVisionRuntime(config = null, chat = compatibleChat()))
    }

    @Test
    fun `a vision model of its own gives a blind chat model eyes and a client to close`() {
        val vision =
            resolveVisionRuntime(
                config = LlmProviderConfig.OpenAi(apiKey = "key", model = "gpt-5.4-mini", requestTimeout = TIMEOUT),
                chat = compatibleChat(),
            )

        assertEquals("gpt-5.4-mini", vision?.model?.id)
        assertEquals(LlmProvider.OPENAI, vision?.model?.provider)
        assertEquals(true, vision?.ownClient)
    }

    // a model named for the role is taken at its word about seeing, whatever its provider says of itself
    @Test
    fun `a compatible model named for vision is assumed to see`() {
        val vision =
            resolveVisionRuntime(
                config =
                    LlmProviderConfig.OpenAiCompatible(
                        baseUrl = "https://api.deepseek.com",
                        apiKey = "key",
                        model = "deepseek-flash",
                        requestTimeout = TIMEOUT,
                    ),
                chat = compatibleChat(),
            )

        assertTrue(vision?.model?.seesImages == true)
    }

    // the chat runtime handed in carries whatever wraps the chat client — the fallback, say — and that
    // wrapper is what vision rides on, not the bare client
    @Test
    fun `vision on the chat model uses the client it was handed`() {
        val wrapped = FakeLlmClient()
        val chat = openAiChat("gpt-5.4-mini").copy(client = wrapped)

        val vision = resolveVisionRuntime(config = null, chat = chat)

        assertSame(wrapped, vision?.client)
        assertFalse(vision?.ownClient == true)
    }

    private fun openAiChat(model: String): LlmRuntime =
        resolveLlmRuntime(LlmProviderConfig.OpenAi(apiKey = "key", model = model, requestTimeout = TIMEOUT))

    private fun compatibleChat(): LlmRuntime =
        resolveLlmRuntime(
            LlmProviderConfig.OpenAiCompatible(
                baseUrl = "https://example.test",
                apiKey = "key",
                model = "gpt-5.4-mini",
                endpoint = OpenAiEndpoint.COMPLETIONS,
                requestTimeout = TIMEOUT,
            ),
        )

    private fun codexChat(supportsVision: Boolean = true): LlmRuntime =
        resolveLlmRuntime(
            LlmProviderConfig.Codex(model = "gpt-5.6-terra", supportsVision = supportsVision, requestTimeout = TIMEOUT),
            codexAuth = CodexAuthStore(Http.createClient(MockEngine { error("no calls expected") })),
        )

    private companion object {
        val TIMEOUT = 120.seconds
    }
}
