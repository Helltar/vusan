package com.helltar.vusan.telegram

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceTranscriberTest {

    @Test
    fun `wrapAudioTranscript wraps transcript in audio_transcript tag`() {
        val wrapped = wrapAudioTranscript("hello world")

        assertEquals("<audio_transcript>\nhello world\n</audio_transcript>", wrapped)
    }

    @Test
    fun `wrapAudioTranscript trims surrounding whitespace inside the tag`() {
        val wrapped = wrapAudioTranscript("   hello   ")

        assertEquals("<audio_transcript>\nhello\n</audio_transcript>", wrapped)
    }
}
