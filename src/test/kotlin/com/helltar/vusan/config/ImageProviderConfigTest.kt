package com.helltar.vusan.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageProviderConfigTest {

    @Test
    fun `the platform defaults to the current model, and the plan asks for what the codex cli does`() {
        assertEquals("gpt-image-2.6-flare", ImageProviderConfig.OpenAi(apiKey = "sk-test").model)
        assertEquals("gpt-image-2", ImageProviderConfig.Codex().model)
        assertEquals("auto", ImageProviderConfig.Codex().quality)
    }

    @Test
    fun `gifs are drawn by the platform on a model from gpt-image-2_6 on`() {
        fun openAi(model: String) = ImageProviderConfig.OpenAi(apiKey = "sk-test", model = model)

        assertTrue(openAi("gpt-image-2.6-flare").drawsGifs)
        assertTrue(openAi("gpt-image-2.6-sunburst").drawsGifs)
        assertTrue(openAi("gpt-image-3").drawsGifs)

        listOf("gpt-image-1", "gpt-image-1-mini", "gpt-image-1.5", "gpt-image-2", "gpt-image-2-2026-04-21", "gpt-image-2.5-flare")
            .forEach { assertFalse(openAi(it).drawsGifs, it) }

        // the plan's backend refuses gif output whatever the model
        assertFalse(ImageProviderConfig.Codex().drawsGifs)
    }

    @Test
    fun `every documented quality is accepted`() {
        ImageProviderConfig.OpenAi.QUALITIES.forEach { quality ->
            assertEquals(quality, ImageProviderConfig.OpenAi(apiKey = "sk-test", quality = quality).quality)
        }
    }

    @Test
    fun `a quality outside the documented set stops the startup`() {
        assertFailsWith<IllegalArgumentException> { ImageProviderConfig.OpenAi(apiKey = "sk-test", quality = "ultra") }
        assertFailsWith<IllegalArgumentException> { ImageProviderConfig.OpenAi(apiKey = "sk-test", model = " ") }
    }

    @Test
    fun `the api provider needs its key`() {
        assertFailsWith<IllegalArgumentException> { ImageProviderConfig.OpenAi(apiKey = "  ") }
    }
}
