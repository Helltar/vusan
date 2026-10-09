package com.helltar.vusan.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImageProviderConfigTest {

    @Test
    fun `both providers default to the same current model`() {
        assertEquals("gpt-image-2.5-flare", ImageProviderConfig.OpenAi(apiKey = "sk-test").model)
        assertEquals("gpt-image-2.5-flare", ImageProviderConfig.Codex().model)
    }

    @Test
    fun `every documented quality is accepted by both providers`() {
        ImageProviderConfig.ALLOWED_QUALITIES.forEach { quality ->
            assertEquals(quality, ImageProviderConfig.OpenAi(apiKey = "sk-test", quality = quality).quality)
            assertEquals(quality, ImageProviderConfig.Codex(quality = quality).quality)
        }
    }

    @Test
    fun `a quality outside the documented set stops the startup`() {
        assertFailsWith<IllegalArgumentException> { ImageProviderConfig.Codex(quality = "ultra") }
        assertFailsWith<IllegalArgumentException> { ImageProviderConfig.OpenAi(apiKey = "sk-test", model = " ") }
    }

    @Test
    fun `the api provider needs its key`() {
        assertFailsWith<IllegalArgumentException> { ImageProviderConfig.OpenAi(apiKey = "  ") }
    }
}
