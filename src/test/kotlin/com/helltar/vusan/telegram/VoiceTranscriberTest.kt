package com.helltar.vusan.telegram

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceTranscriberTest {

    @Test
    fun `wrapVoiceTranscript wraps transcript in voice_transcript tag`() {
        val wrapped = wrapVoiceTranscript("hello world")

        assertEquals("<voice_transcript>\nhello world\n</voice_transcript>", wrapped)
    }

    @Test
    fun `wrapVoiceTranscript trims surrounding whitespace inside the tag`() {
        val wrapped = wrapVoiceTranscript("   hello   ")

        assertEquals("<voice_transcript>\nhello\n</voice_transcript>", wrapped)
    }
}
