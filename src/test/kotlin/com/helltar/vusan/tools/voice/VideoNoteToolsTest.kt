package com.helltar.vusan.tools.voice

import com.helltar.vusan.agent.TurnShelf
import com.helltar.vusan.config.ElevenLabsTtsConfig
import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.ChatCapabilities
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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

        val result = tools.speakAsVideoNote("a".repeat(VideoNoteTools.VIDEO_NOTE_MAX_CHARS + 1))

        assertTrue(result.contains("exceeds the ${VideoNoteTools.VIDEO_NOTE_MAX_CHARS}-character limit"))
        assertFalse(synthesized)
        assertTrue(outbox.pending.isEmpty())
    }

    // producing for a chat that refuses the kind would be paid for and then dropped, so it is answered first;
    // kept for a later call, the same words are synthesized and queued nowhere
    @Test
    fun `a chat that refuses round videos is answered before synthesizing, unless the video is kept instead`() = runBlocking {
        val outbox = BotOutbox(ChatCapabilities(videoNotes = false))
        var synthesized = 0
        val tools = videoNoteTools(outbox, onSynthesize = { synthesized++ }) { _, _ -> byteArrayOf(4) }

        assertContains(tools.speakAsVideoNote("Hello there"), "does not accept round video messages")
        assertEquals(0, synthesized)

        val kept = withContext(TurnShelf().open()) { tools.speakAsVideoNote("Hello there", send = false) }

        assertEquals(1, synthesized)
        assertContains(kept, "`#1/1`")
        assertTrue(outbox.pending.isEmpty())
    }

    private fun videoNoteTools(
        outbox: BotOutbox,
        onSynthesize: () -> Unit = {},
        renderer: VideoNoteRenderer,
    ): VideoNoteTools {
        val http =
            Http.createClient(
                MockEngine {
                    onSynthesize()

                    respond(
                        content = ByteReadChannel(speech),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"),
                    )
                },
            )

        return VideoNoteTools(ElevenLabsTtsClient(http, "sk-test"), config, portrait, outbox, renderer)
    }
}
