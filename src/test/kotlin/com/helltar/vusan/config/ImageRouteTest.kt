package com.helltar.vusan.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class ImageRouteTest {

    private val codex = LlmProviderConfig.Codex(model = "gpt-5.6-terra", requestTimeout = 120.seconds)

    private val apiKeyProvider =
        LlmProviderConfig.Hosted(
            provider = HostedLlmProvider.OPENAI,
            apiKey = "key",
            model = "gpt-5.4-mini",
            requestTimeout = 120.seconds
        )

    @Test
    fun `a paid image key wins over the subscription`() {
        // the key bills separately; the codex route would spend the same quota the chat turns run on
        assertEquals(ImageRoute.PLATFORM, resolveImageRoute(hasImageApiKey = true, provider = codex))
    }

    @Test
    fun `the subscription covers image generation when no key is set`() {
        assertEquals(ImageRoute.CODEX, resolveImageRoute(hasImageApiKey = false, provider = codex))
    }

    @Test
    fun `the subscription route can be switched off`() {
        val switchedOff = codex.copy(imageGeneration = false)

        // without a key that leaves image generation off, as on any other provider
        assertNull(resolveImageRoute(hasImageApiKey = false, provider = switchedOff))
        // and a key still brings it back, on its own bill
        assertEquals(ImageRoute.PLATFORM, resolveImageRoute(hasImageApiKey = true, provider = switchedOff))
    }

    @Test
    fun `without a key or a codex session the tools stay disabled`() {
        assertNull(resolveImageRoute(hasImageApiKey = false, provider = apiKeyProvider))
    }

    @Test
    fun `each route brings its own default model`() {
        // the codex backend renders with gpt-image-2 and does not serve the platform catalog
        assertEquals("gpt-image-2", defaultImageModel(ImageRoute.CODEX))
        assertEquals("gpt-image-1.5", defaultImageModel(ImageRoute.PLATFORM))
    }
}
