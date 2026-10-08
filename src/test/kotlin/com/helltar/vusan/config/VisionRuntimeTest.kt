package com.helltar.vusan.config

import com.helltar.vusan.infra.Http
import com.helltar.vusan.llm.FakeLlmClient
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.openai.OpenAiEndpoint
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertNull(resolveVisionRuntime(config = null, chat = codexChat(seesImages = false)))
    }

    // the server behind LLM_BASE_URL can serve anything, so a compatible chat model sees only when its
    // server's own model list said so at startup, as deepseek's does of flash
    @Test
    fun `an openai-compatible chat model sees only on its server's word`() {
        assertNull(resolveVisionRuntime(config = null, chat = compatibleChat()))

        val sighted =
            resolveLlmRuntime(
                LlmProviderConfig.OpenAiCompatible(
                    baseUrl = "https://api.deepseek.com",
                    apiKey = "key",
                    model = "deepseek-flash",
                    requestTimeout = TIMEOUT,
                    seesImages = true,
                ),
            )

        assertEquals(sighted.model, resolveVisionRuntime(config = null, chat = sighted)?.model)
    }

    @Test
    fun `a vision model of its own gives a blind chat model eyes and a client to close`() {
        val vision =
            resolveVisionRuntime(
                config = LlmProviderConfig.OpenAi(apiKey = "key", model = "gpt-5.4-mini", requestTimeout = TIMEOUT),
                chat = compatibleChat(),
            )

        assertEquals("gpt-5.4-mini", vision?.model?.id)
        assertEquals(true, vision?.ownClient)
    }

    // a vision model the vendor itself says is blind would fail every look, so the startup says so instead
    @Test
    fun `a vision model its vendor says takes no images stops the startup`() {
        val textOnly =
            LlmProviderConfig.OpenAiCompatible(
                baseUrl = "https://api.deepseek.com",
                apiKey = "key",
                model = "deepseek-v4-pro",
                requestTimeout = TIMEOUT,
                seesImages = false,
                envPrefix = "VISION",
            )

        val failure = assertFailsWith<IllegalArgumentException> { resolveVisionRuntime(config = textOnly, chat = compatibleChat()) }
        assertContains(failure.message.orEmpty(), "VISION_MODEL=[deepseek-v4-pro]")

        val codexTextOnly = LlmProviderConfig.Codex(model = "text-model", seesImages = false, requestTimeout = TIMEOUT, envPrefix = "VISION")
        assertFailsWith<IllegalArgumentException> { resolveVisionRuntime(config = codexTextOnly, chat = compatibleChat()) }
    }

    // a model named for the role is taken at its word about seeing when nothing says otherwise
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

    // a picture is looked at once, so it is worth no cache write; on the chat model a look keeps the model's
    // own effort rather than the one tuned for turns, and a vision model of its own gets the one set for it
    @Test
    fun `a look is cached nowhere and carries the effort set for vision`() {
        val chat = resolveLlmRuntime(LlmProviderConfig.OpenAi(apiKey = "key", model = "gpt-5.6-sol", reasoningEffort = ReasoningEffort.XHIGH, requestTimeout = TIMEOUT))
        val onChat = resolveVisionRuntime(config = null, chat = chat)

        assertEquals(false, onChat?.options?.cachePrompt)
        assertNull(onChat?.options?.reasoningEffort)
        assertEquals("vusan-vision", onChat?.options?.promptCacheKey)

        val own =
            resolveVisionRuntime(
                config = LlmProviderConfig.Anthropic(apiKey = "key", model = "claude-haiku-5-5", reasoningEffort = ReasoningEffort.LOW, requestTimeout = TIMEOUT),
                chat = compatibleChat(),
            )

        assertEquals(ReasoningEffort.LOW, own?.options?.reasoningEffort)
        assertEquals(false, own?.options?.cachePrompt)
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

    private fun codexChat(seesImages: Boolean = true): LlmRuntime =
        resolveLlmRuntime(
            LlmProviderConfig.Codex(model = "gpt-5.6-terra", seesImages = seesImages, requestTimeout = TIMEOUT),
            codexAuth = CodexAuthStore(Http.createClient(MockEngine { error("no calls expected") })),
        )

    private companion object {
        val TIMEOUT = 120.seconds
    }
}
