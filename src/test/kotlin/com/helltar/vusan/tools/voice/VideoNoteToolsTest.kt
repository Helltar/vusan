package com.helltar.vusan.tools.voice

import com.helltar.vusan.config.ElevenLabsTtsConfig
import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class VideoNoteToolsTest {

    private val config = ElevenLabsTtsConfig(model = "eleven_v3", voiceId = "voice-test")
    private val speech = byteArrayOf(1, 2, 3)
    private val portrait = byteArrayOf(9, 9)

    @Test
    fun `speakAsVideoNote queues the rendered round video`() = runBlocking {
        val outbox = BotOutbox()
        val video = byteArrayOf(4, 5, 6, 7)
        var rendered: Pair<ByteArray, ByteArray>? = null

        val tools =
            videoNoteTools(outbox) { portraitBytes, speechBytes ->
                rendered = portraitBytes to speechBytes
                video
            }

        val result = tools.speakAsVideoNote("  Hello there  ")

        assertTrue(result.startsWith("Round video message queued"))
        assertContentEquals(portrait, rendered?.first)
        assertContentEquals(speech, rendered?.second)

        val output = assertIs<BotOutput.VideoNote>(outbox.pending.single().output)
        assertContentEquals(video, output.bytes)
        assertEquals(VIDEO_NOTE_SIZE, output.size)
    }

    @Test
    fun `speakAsVideoNote falls back to a voice message when the render fails`() = runBlocking {
        val outbox = BotOutbox()
        val tools = videoNoteTools(outbox) { _, _ -> null }

        val result = tools.speakAsVideoNote("Hello there")

        assertTrue(result.startsWith("Rendering the round video failed"))

        val output = assertIs<BotOutput.Voice>(outbox.pending.single().output)
        assertContentEquals(speech, output.bytes)
    }

    @Test
    fun `speakAsVideoNote rejects text over the limit before synthesizing`() = runBlocking {
        val outbox = BotOutbox()
        var synthesized = false

        val tools =
            videoNoteTools(outbox, onSynthesize = { synthesized = true }) { _, _ ->
                error("render must not run")
            }

        val result = tools.speakAsVideoNote("a".repeat(VoiceTools.VOICE_TOOLS_MAX_CHARS + 1))

        assertTrue(result.contains("exceeds the ${VoiceTools.VOICE_TOOLS_MAX_CHARS}-character limit"))
        assertFalse(synthesized)
        assertTrue(outbox.pending.isEmpty())
    }

    private fun videoNoteTools(
        outbox: BotOutbox,
        onSynthesize: () -> Unit = {},
        renderer: VideoNoteRenderer
    ): VideoNoteTools {
        val http =
            Http.createClient(
                MockEngine {
                    onSynthesize()

                    respond(
                        content = ByteReadChannel(speech),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "audio/mpeg")
                    )
                }
            )

        return VideoNoteTools(ElevenLabsTtsClient(http, "sk-test"), config, portrait, outbox, renderer)
    }
}
