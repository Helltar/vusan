package com.helltar.vusan.tools.speech

import com.helltar.vusan.config.OpenAiSttConfig
import com.helltar.vusan.infra.Http
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.stt.OpenAiWhisperClient
import com.helltar.vusan.tools.ToolFailure
import com.helltar.vusan.tools.vision.VideoSampler
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechToolsTest {

    private val sent = mutableListOf<String>()
    private val cuts = mutableListOf<Int>()

    private val whisper =
        OpenAiWhisperClient(
            Http.createClient(
                MockEngine { request ->
                    sent += request.body.toByteArray().decodeToString()
                    respond("""{"text":" the toast was short "}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
            OpenAiSttConfig(apiKey = "test", model = "gpt-transcribe"),
        )

    private fun tools(attached: AttachedFile? = null, audio: ByteArray? = byteArrayOf(9)) =
        SpeechTools(whisper, attached, CuttingSampler(audio))

    // records the cut asked for and never samples a frame, since speech wants none
    private inner class CuttingSampler(private val audio: ByteArray?) : VideoSampler {

        override suspend fun sampleFrames(video: ByteArray, durationSeconds: Int?, maxFrames: Int): List<ByteArray> =
            error("no frames are wanted for speech")

        override suspend fun extractAudio(video: ByteArray, maxSeconds: Int?): ByteArray? {
            cuts += requireNotNull(maxSeconds)

            return audio
        }
    }

    @Test
    fun `a named recording is cut to the speech budget and transcribed in the language given`() = runBlocking {
        val result = tools().transcribeAudio(file = recording("speech.ogg"), language = "KY")

        assertEquals(listOf(OpenAiSttConfig.MAX_DURATION_SECONDS.toInt()), cuts)
        assertContains(sent.single(), """name="language"""")
        assertContains(sent.single(), "ky")
        assertContains(result, "<transcript>\nthe toast was short\n</transcript>")
    }

    @Test
    fun `without a file it takes the attachment, and without a language it asks for none`() = runBlocking {
        val result = tools(attached = recording("voice.m4a")).transcribeAudio()

        assertContains(result, "`voice.m4a`")
        assertFalse(sent.single().contains("""name="language""""))
    }

    @Test
    fun `nothing attached or named is said so without a request`() = runBlocking {
        assertEquals("No recording is attached in this turn, and none was named.", tools().transcribeAudio())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a recording with no sound in it is not sent anywhere`() = runBlocking {
        assertEquals("No sound could be read out of `silent.mp4`.", tools(audio = null).transcribeAudio(recording("silent.mp4")))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a language that is not a two-letter code is refused`() = runBlocking {
        assertFailsWith<ToolFailure> { tools().transcribeAudio(recording("speech.ogg"), language = "kyrgyz") }
        assertTrue(sent.isEmpty())
    }

    private fun recording(name: String) =
        AttachedFile(name = name, fileSizeBytes = 3, mimeType = null, kind = AttachedFileKind.OTHER, loadBytes = { byteArrayOf(1, 2, 3) })
}
