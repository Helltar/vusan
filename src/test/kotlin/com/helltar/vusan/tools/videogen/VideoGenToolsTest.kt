package com.helltar.vusan.tools.videogen

import com.helltar.vusan.agent.TurnShelf
import com.helltar.vusan.config.VideoProviderConfig
import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.imagegen.SelfImage
import com.helltar.vusan.tools.imagegen.SourceImage
import com.helltar.vusan.tools.toolFailure
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

private val VIDEO = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".encodeToByteArray() + ByteArray(8)
private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(13)
private const val VIDEO_URL = "https://vidgen.x.ai/bucket/v.mp4"

class VideoGenToolsTest {

    private class Xai {
        val started = mutableListOf<Pair<String, JsonObject>>()
        var polls = 0
        val outcomes = ArrayDeque<String>()
    }

    private fun done(duration: Int = 6, moderated: Boolean = true) =
        """{"status":"done","progress":100,"video":{"url":"$VIDEO_URL","duration":$duration,"respect_moderation":$moderated},"model":"m"}"""

    private fun failed(code: String) = """{"status":"failed","error":{"code":"$code","message":"$code happened"}}"""

    private fun tools(
        xai: Xai,
        outbox: BotOutbox = BotOutbox(),
        config: VideoProviderConfig.Xai = VideoProviderConfig.Xai(apiKey = "xai-test"),
        attached: List<AttachedFile> = emptyList(),
        self: SelfImage? = null,
    ): VideoGenTools {
        val http =
            Http.createClient(
                MockEngine { request ->
                    val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    val path = request.url.encodedPath

                    when {
                        request.url.toString() == VIDEO_URL -> respond(VIDEO, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "video/mp4"))

                        request.method == HttpMethod.Post -> {
                            xai.started += path to Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject
                            respond("""{"request_id":"job-${xai.started.size}"}""", HttpStatusCode.OK, json)
                        }

                        // the first look at every job finds it still rendering
                        else -> {
                            xai.polls++
                            if (xai.polls % 2 == 1) respond("""{"status":"pending","progress":40}""", HttpStatusCode.Accepted, json)
                            else respond(xai.outcomes.removeFirstOrNull() ?: done(), HttpStatusCode.OK, json)
                        }
                    }
                },
            )

        return VideoGenTools(XaiVideoClient(http, config, FileDownloadClient(http), pollInterval = Duration.ZERO), outbox, attached, self)
    }

    private fun picture(name: String = "cat.jpg") = AttachedFile(name, JPEG.size.toLong(), "image/jpeg", AttachedFileKind.IMAGE) { JPEG }

    private fun clip(name: String = "clip.mp4", duration: Int? = 5) =
        AttachedFile(name, VIDEO.size.toLong(), "video/mp4", AttachedFileKind.VIDEO, durationSeconds = duration) { VIDEO }

    private val JsonObject.prompt: String
        get() = getValue("prompt").jsonPrimitive.content

    @Test
    fun `a video from a description is started, waited for, downloaded and queued`() = runBlocking {
        val xai = Xai()
        val outbox = BotOutbox()

        val result = tools(xai, outbox).generateVideo("snow falls on a pine forest at night")

        val (path, body) = xai.started.single()
        assertEquals("/v1/videos/generations", path)
        assertEquals("grok-imagine-video-1.5", body.getValue("model").jsonPrimitive.content)
        assertEquals(6, body.getValue("duration").jsonPrimitive.content.toInt())
        assertEquals("720p", body.getValue("resolution").jsonPrimitive.content)
        assertEquals("16:9", body.getValue("aspect_ratio").jsonPrimitive.content)
        assertNull(body["generate_audio"], "sound is the default, so nothing asks for it")
        assertNull(body["image"])
        assertNull(body["reference_images"])
        assertEquals(2, xai.polls, "asked again until the job was done")

        val video = assertIs<BotOutput.Video>(outbox.pending.single().output)
        assertContentEquals(VIDEO, video.bytes)
        assertEquals(6, video.durationSeconds)
        assertContains(result, "queued")
    }

    // xai numbers every image a request carries: the start frame first, then the references, then the bot
    @Test
    fun `the pictures are numbered the way xai counts them, and the bot speaks with its voice`() = runBlocking {
        val xai = Xai()
        val config = VideoProviderConfig.Xai(apiKey = "xai-test", resolution = "1080p", voice = "eve")
        val self = SelfImage(SourceImage(JPEG, "me.jpg", "image/jpeg"), "Wears a green scarf.")

        tools(xai, config = config, self = self).generateVideo(
            "<SELF> sits beside the cat from <IMAGE_0>",
            startFrame = picture("hill.jpg"),
            references = listOf(picture()),
            withYourself = true,
            sound = false,
        )

        val body = xai.started.single().second
        assertContains(body.prompt, "<IMAGE_2> sits beside the cat from <IMAGE_1>")
        assertContains(body.prompt, "About <IMAGE_2>: Wears a green scarf.")
        assertContains(body.prompt, "When <IMAGE_2> speaks, it is with the voice <AUDIO_0>.")
        assertTrue(body.getValue("image").jsonObject.getValue("url").jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
        assertEquals(2, body.getValue("reference_images").jsonArray.size)
        assertEquals("eve", body.getValue("reference_audios").jsonArray.single().jsonObject.getValue("voice_id").jsonPrimitive.content)
        assertEquals("720p", body.getValue("resolution").jsonPrimitive.content, "reference-to-video renders at most 720p")
        assertNull(body["aspect_ratio"], "the start frame decides the shape")
        assertEquals(false, body.getValue("generate_audio").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `the prompt is renumbered for xai and refused when it names what it was not given`() {
        assertEquals("<IMAGE_1> and <IMAGE_2>", xaiVideoPrompt("<IMAGE_0> and <IMAGE_1>", references = 2, offset = 1, selfName = null))
        assertEquals("<IMAGE_1> waves", xaiVideoPrompt("<SELF> waves", references = 1, offset = 0, selfName = "<IMAGE_1>"))
        assertEquals("the main character waves", xaiVideoPrompt("<SELF> waves", references = 0, offset = 0, selfName = "the main character"))
        assertNull(xaiVideoPrompt("<IMAGE_1> dances", references = 1, offset = 0, selfName = null))
        assertNull(xaiVideoPrompt("<SELF> dances", references = 0, offset = 0, selfName = null))
    }

    @Test
    fun `a prompt naming a missing picture is refused before anything is started`() = runBlocking {
        val xai = Xai()

        val message = toolFailure { tools(xai).generateVideo("<IMAGE_1> dances", references = listOf(picture())) }

        assertContains(message, "names a picture it was not given")
        assertTrue(xai.started.isEmpty())
    }

    // the first job measured failed on xai's side, and the same request went through on the next try
    @Test
    fun `a job xai failed on its own side is started once more`() = runBlocking {
        val xai = Xai().apply { outcomes += failed("internal_error") }
        val outbox = BotOutbox()

        tools(xai, outbox).generateVideo("a fox in the snow")

        assertEquals(2, xai.started.size)
        assertIs<BotOutput.Video>(outbox.pending.single().output)
    }

    @Test
    fun `a job failed over the request itself is not repeated`() = runBlocking {
        val xai = Xai().apply { outcomes += failed("invalid_argument") }

        val message = toolFailure { tools(xai).generateVideo("a fox in the snow") }

        assertContains(message, "invalid_argument happened")
        assertEquals(1, xai.started.size)
    }

    @Test
    fun `a video moderation withheld is reported, not sent`() = runBlocking {
        val xai = Xai().apply { outcomes += done(moderated = false) }
        val outbox = BotOutbox()

        val result = tools(xai, outbox).generateVideo("a fox in the snow")

        assertContains(result, "moderation rejected")
        assertTrue(outbox.pending.isEmpty())
    }

    // what xai answered on 2026-10-21 for a rendered video its moderation rejected
    @Test
    fun `a moderation refusal sent as a 400 is reported, not failed`() = runBlocking {
        val xai = Xai()
        val outbox = BotOutbox()
        val http =
            Http.createClient(
                MockEngine { request ->
                    val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

                    if (request.method == HttpMethod.Post) {
                        xai.started += request.url.encodedPath to JsonObject(emptyMap())
                        respond("""{"request_id":"job-1"}""", HttpStatusCode.OK, json)
                    } else {
                        respond("""{"code":"imagine:content-moderated","error":"Generated video rejected by content moderation."}""", HttpStatusCode.BadRequest, json)
                    }
                },
            )
        val tools = VideoGenTools(XaiVideoClient(http, VideoProviderConfig.Xai(apiKey = "xai-test"), FileDownloadClient(http), pollInterval = Duration.ZERO), outbox)

        val result = tools.generateVideo("a fox in the snow")

        assertContains(result, "moderation rejected")
        assertEquals(1, xai.started.size, "a refusal is not retried")
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `an edit takes the attached video and runs on the one model that edits`() = runBlocking {
        val xai = Xai().apply { outcomes += done(duration = 5) }
        val outbox = BotOutbox()

        tools(xai, outbox, attached = listOf(picture(), clip())).editVideo("make it night")

        val (path, body) = xai.started.single()
        assertEquals("/v1/videos/edits", path)
        assertEquals("grok-imagine-video", body.getValue("model").jsonPrimitive.content)
        assertTrue(body.getValue("video").jsonObject.getValue("url").jsonPrimitive.content.startsWith("data:video/mp4;base64,"))
        assertEquals(5, assertIs<BotOutput.Video>(outbox.pending.single().output).durationSeconds)
    }

    @Test
    fun `a file that is not an mp4 video is refused`() = runBlocking {
        val xai = Xai()
        val webm = AttachedFile("clip.webm", 16, "video/webm", AttachedFileKind.VIDEO) { ByteArray(16) }

        assertContains(toolFailure { tools(xai).editVideo("x", video = picture()) }, "is not a video")
        assertContains(toolFailure { tools(xai).editVideo("x", video = webm) }, "is not an MP4 video")
        assertContains(toolFailure { tools(xai).editVideo("x") }, "no video is attached")
        assertTrue(xai.started.isEmpty())
    }

    // the answer states the added seconds alone, so the length is the source's plus what was asked for
    @Test
    fun `an extension kept for later is on the shelf, queued nowhere, and as long as both parts`() = runBlocking {
        val xai = Xai().apply { outcomes += done(duration = 2) }
        val outbox = BotOutbox()
        val shelf = TurnShelf()
        val call = shelf.open()

        val result = withContext(call) { tools(xai, outbox).extendVideo("the fox howls", video = clip(duration = 5), seconds = 2, send = false) }
        call.close(result)

        val body = xai.started.single().second
        assertEquals("/v1/videos/extensions", xai.started.single().first)
        assertEquals(2, body.getValue("duration").jsonPrimitive.content.toInt())
        assertTrue(outbox.pending.isEmpty())
        assertContains(result, "kept as `#1/1`")
        assertContentEquals(VIDEO, shelf.open().file("#1/1").loadBytes())
    }

    @Test
    fun `a chat that takes no videos and a length out of range start nothing`() = runBlocking {
        val xai = Xai()

        val refused = tools(xai, BotOutbox(ChatCapabilities(videos = false))).generateVideo("a fox")

        assertContains(refused, "does not accept videos")
        assertContains(toolFailure { tools(xai).generateVideo("a fox", seconds = 16) }, "seconds must be from 1 to 15")
        assertContains(toolFailure { tools(xai).extendVideo("on", video = clip(), seconds = 1) }, "seconds must be from 2 to 10")
        assertContains(toolFailure { tools(xai).generateVideo("a fox", aspectRatio = "7:5") }, "aspectRatio must be one of")
        assertFalse(xai.started.isNotEmpty())
    }
}
