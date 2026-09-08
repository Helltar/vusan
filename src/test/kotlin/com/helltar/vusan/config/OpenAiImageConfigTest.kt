package com.helltar.vusan.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAiImageConfigTest {

    @Test
    fun `the gpt-image-2_5 quality tiers are accepted`() {
        listOf("xhigh", "max").forEach { quality ->
            assertEquals(quality, OpenAiImageConfig(model = "gpt-image-2.5-flare", quality = quality).quality)
        }
    }

    @Test
    fun `an unknown quality is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            OpenAiImageConfig(model = "gpt-image-1.5", quality = "ultra")
        }
    }

    @Test
    fun `moderation accepts only the two settings the image api offers`() {
        assertEquals("low", OpenAiImageConfig(model = "gpt-image-1.5", quality = "medium", moderation = "low").moderation)

        assertFailsWith<IllegalArgumentException> {
            OpenAiImageConfig(model = "gpt-image-1.5", quality = "medium", moderation = "off")
        }
    }
}
