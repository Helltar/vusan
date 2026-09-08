package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.config.OpenAiImageConfig
import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImageGenToolsTest {

    private val config = OpenAiImageConfig(model = "gpt-image-1.5", quality = "medium")
    private val imageBytes = byteArrayOf(4, 2, 0)

    // records the `size` of the last request the tool issued, so orientation mapping can be asserted.
    private class SizeProbe {
        var size: String? = null
    }

    // records the endpoint the tool picked: a self-portrait has to leave through /edits with the
    // reference photo, everything else through /generations.
    private class RouteProbe {
        var path: String? = null
    }

    // records the multipart upload an edit built, which is where the source images end up.
    private class EditProbe {
        var form: String = ""

        val imageParts: Int
            get() = Regex("""name="image\[]"""").findAll(form).count()

        fun filenames(): List<String> = Regex("""filename="([^"]+)"""").findAll(form).map { it.groupValues[1] }.toList()
    }

    private fun tools(
        outbox: BotOutbox,
        probe: SizeProbe = SizeProbe(),
        config: OpenAiImageConfig = this.config
    ): ImageGenTools {
        val encoded = Base64.getEncoder().encodeToString(imageBytes)
        val http =
            Http.createClient(
                MockEngine { request ->
                    val body = assertIs<TextContent>(request.body)
                    probe.size = Json.parseToJsonElement(body.text).jsonObject["size"]?.jsonPrimitive?.content
                    respond(
                        content = """{"data":[{"b64_json":"$encoded"}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        return ImageGenTools(OpenAiImageClient(http, ImageAuth.ApiKey("sk-test")), config, outbox)
    }

    private fun selfTools(outbox: BotOutbox, selfImage: SelfImage?, probe: RouteProbe = RouteProbe()): ImageGenTools {
        val encoded = Base64.getEncoder().encodeToString(imageBytes)
        val http =
            Http.createClient(
                MockEngine { request ->
                    probe.path = request.url.encodedPath

                    respond(
                        content = """{"data":[{"b64_json":"$encoded"}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )

        return ImageGenTools(
            OpenAiImageClient(http, ImageAuth.ApiKey("sk-test")), config, outbox, selfImage = selfImage
        )
    }

    private fun reference() = SourceImage(byteArrayOf(1, 1, 1), "avatar.jpg", "image/jpeg")

    private fun editTools(
        outbox: BotOutbox,
        vararg attached: AttachedFile,
        selfImage: SelfImage? = null,
        probe: EditProbe = EditProbe()
    ): ImageGenTools {
        val encoded = Base64.getEncoder().encodeToString(imageBytes)
        val http =
            Http.createClient(
                MockEngine { request ->
                    probe.form = String(request.body.toByteArray(), Charsets.ISO_8859_1)

                    respond(
                        content = """{"data":[{"b64_json":"$encoded"}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        return ImageGenTools(
            OpenAiImageClient(http, ImageAuth.ApiKey("sk-test")), config, outbox, attached.toList(), selfImage
        )
    }

    private fun imageAttachment(
        name: String = "photo.jpg",
        mimeType: String? = "image/jpeg",
        kind: AttachedFileKind = AttachedFileKind.IMAGE,
        fileSizeBytes: Long? = null,
        bytes: ByteArray = byteArrayOf(7, 7, 7)
    ) = AttachedFile(
        name = name,
        fileSizeBytes = fileSizeBytes,
        mimeType = mimeType,
        kind = kind,
        loadBytes = { bytes }
    )

    @Test
    fun `generateImage enqueues a photo with the decoded bytes`() = runBlocking {
        val outbox = BotOutbox()
        val result = tools(outbox).generateImage("a neon city skyline")

        val photo = assertIs<BotOutput.Photo>(outbox.pending.single().output)
        assertEquals("image.png", photo.filename)
        assertContentEquals(imageBytes, photo.bytes)
        assertContains(result, "Image queued")
    }

    @Test
    fun `orientation maps to the requested image size`() = runBlocking {
        val square = SizeProbe()
        tools(BotOutbox(), square).generateImage("x")
        assertEquals("1024x1024", square.size)

        val portrait = SizeProbe()
        tools(BotOutbox(), portrait).generateImage("x", orientation = "portrait")
        assertEquals("1024x1536", portrait.size)

        val landscape = SizeProbe()
        tools(BotOutbox(), landscape).generateImage("x", orientation = "landscape")
        assertEquals("1536x1024", landscape.size)
    }

    @Test
    fun `blank prompt enqueues nothing`() = runBlocking {
        val outbox = BotOutbox()
        val result = tools(outbox).generateImage("   ")

        assertTrue(outbox.pending.isEmpty())
        assertContains(result, "empty")
    }

    @Test
    fun `over-limit prompt is rejected without generating`() = runBlocking {
        val outbox = BotOutbox()
        val result = tools(outbox).generateImage("a".repeat(ImageGenTools.IMAGE_PROMPT_MAX_CHARS + 1))

        assertTrue(outbox.pending.isEmpty())
        assertContains(result, "exceeds")
    }

    @Test
    fun `editImage enqueues the edited photo when an image is attached`() = runBlocking {
        val outbox = BotOutbox()
        val result = editTools(outbox, imageAttachment()).editImage("add a wizard hat")

        val photo = assertIs<BotOutput.Photo>(outbox.pending.single().output)
        assertEquals("image.png", photo.filename)
        assertContentEquals(imageBytes, photo.bytes)
        assertContains(result, "Edited image queued")
        assertContains(result, "auto")
    }

    @Test
    fun `editImage orientation override maps to the requested size`() = runBlocking {
        val outbox = BotOutbox()
        val result = editTools(outbox, imageAttachment()).editImage("make it wide", orientation = "landscape")

        assertContains(result, "1536x1024")
    }

    @Test
    fun `editImage without an attachment enqueues nothing`() = runBlocking {
        val outbox = BotOutbox()
        val result = editTools(outbox).editImage("add a hat")

        assertTrue(outbox.pending.isEmpty())
        assertContains(result, "No image is attached")
    }

    @Test
    fun `editImage rejects a non-image attachment`() = runBlocking {
        val outbox = BotOutbox()
        val attachment = imageAttachment(name = "notes.txt", mimeType = "text/plain", kind = AttachedFileKind.OTHER)
        val result = editTools(outbox, attachment).editImage("add a hat")

        assertTrue(outbox.pending.isEmpty())
        assertContains(result, "not an image")
    }

    @Test
    fun `editImage rejects an unsupported image type`() = runBlocking {
        val outbox = BotOutbox()
        val attachment = imageAttachment(name = "sticker.gif", mimeType = "image/gif")
        val result = editTools(outbox, attachment).editImage("add a hat")

        assertTrue(outbox.pending.isEmpty())
        assertContains(result, "not a supported image type")
    }

    @Test
    fun `editImage falls back to the filename extension when the mime type is missing`() = runBlocking {
        val outbox = BotOutbox()
        val attachment = imageAttachment(name = "art.png", mimeType = null)
        val result = editTools(outbox, attachment).editImage("brighten it")

        assertIs<BotOutput.Photo>(outbox.pending.single().output)
        assertContains(result, "Edited image queued")
    }

    @Test
    fun `blank edit instruction enqueues nothing`() = runBlocking {
        val outbox = BotOutbox()
        val result = editTools(outbox, imageAttachment()).editImage("   ")

        assertTrue(outbox.pending.isEmpty())
        assertContains(result, "empty")
    }

    @Test
    fun `self-portrait renders the reference photo through the edit endpoint`() = runBlocking {
        val outbox = BotOutbox()
        val probe = RouteProbe()
        val selfImage = SelfImage(reference(), appearance = null)

        val result = selfTools(outbox, selfImage, probe).generateImage("on a night tram", selfPortrait = true)

        assertEquals("/v1/images/edits", probe.path)
        assertIs<BotOutput.Photo>(outbox.pending.single().output)
        assertContains(result, "Image queued")
    }

    @Test
    fun `a picture that is not of the bot ignores the reference photo`() = runBlocking {
        val probe = RouteProbe()

        selfTools(BotOutbox(), SelfImage(reference(), appearance = null), probe).generateImage("a neon city")

        assertEquals("/v1/images/generations", probe.path)
    }

    @Test
    fun `self-portrait without a reference photo still generates`() = runBlocking {
        val outbox = BotOutbox()
        val probe = RouteProbe()
        val selfImage = SelfImage(reference = null, appearance = "tall, short bleached hair")

        val result = selfTools(outbox, selfImage, probe).generateImage("on a night tram", selfPortrait = true)

        assertEquals("/v1/images/generations", probe.path)
        assertIs<BotOutput.Photo>(outbox.pending.single().output)
        assertContains(result, "Image queued")
    }

    @Test
    fun `self-portrait is a plain generation when nothing describes the bot`() = runBlocking {
        val probe = RouteProbe()

        selfTools(BotOutbox(), selfImage = null, probe = probe).generateImage("a selfie", selfPortrait = true)

        assertEquals("/v1/images/generations", probe.path)
    }

    @Test
    fun `a moderation block is answered with what to do next instead of a failure dump`() = runBlocking {
        val outbox = BotOutbox()
        val http =
            Http.createClient(
                MockEngine {
                    respond(
                        content = """{"error":{"code":"moderation_blocked","message":"rejected",""" +
                                """"moderation_details":{"moderation_stage":"input","categories":["violence"]}}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        val tools = ImageGenTools(OpenAiImageClient(http, ImageAuth.ApiKey("sk-test")), config, outbox)

        val result = tools.generateImage("a duel at dawn")

        assertTrue(outbox.pending.isEmpty())
        assertContains(result, "content filter")
        assertContains(result, "violence")
        assertFalse(result.contains("HTTP 400"))
    }

    @Test
    fun `editImage sends every attached image as its own source`() = runBlocking {
        val outbox = BotOutbox()
        val probe = EditProbe()
        val tools = editTools(outbox, imageAttachment(name = "one.jpg"), imageAttachment(name = "two.jpg"), probe = probe)

        val result = tools.editImage("put them side by side")

        assertEquals(2, probe.imageParts)
        assertEquals(listOf("one.jpg", "two.jpg"), probe.filenames())
        assertIs<BotOutput.Photo>(outbox.pending.single().output)
        assertContains(result, "2 source image(s)")
    }

    @Test
    fun `editImage skips the parts of an album that are not editable images`() = runBlocking {
        val outbox = BotOutbox()
        val probe = EditProbe()
        val video = imageAttachment(name = "clip.mp4", mimeType = "video/mp4", kind = AttachedFileKind.VIDEO)
        val tools = editTools(outbox, imageAttachment(name = "one.jpg"), video, probe = probe)

        tools.editImage("make it winter")

        assertEquals(listOf("one.jpg"), probe.filenames())
    }

    @Test
    fun `withYourself puts the reference photo first among the sources`() = runBlocking {
        val outbox = BotOutbox()
        val probe = EditProbe()
        val tools =
            editTools(
                outbox,
                imageAttachment(name = "room.jpg"),
                selfImage = SelfImage(reference(), appearance = null),
                probe = probe
            )

        tools.editImage("standing by the window", withYourself = true)

        assertEquals(listOf("avatar.jpg", "room.jpg"), probe.filenames())
    }

    @Test
    fun `withYourself without a reference photo edits only what the user sent`() = runBlocking {
        val outbox = BotOutbox()
        val probe = EditProbe()
        val tools = editTools(outbox, imageAttachment(name = "room.jpg"), probe = probe)

        tools.editImage("standing by the window", withYourself = true)

        assertEquals(listOf("room.jpg"), probe.filenames())
    }

    @Test
    fun `the 16-by-9 framings need a flexible-size model`() = runBlocking {
        val legacy = SizeProbe()
        tools(BotOutbox(), legacy).generateImage("x", orientation = "banner")
        assertEquals("1536x1024", legacy.size)

        val flexible = SizeProbe()
        tools(BotOutbox(), flexible, config.copy(model = "gpt-image-2.5-flare")).generateImage("x", orientation = "story")
        assertEquals("1152x2048", flexible.size)
    }
}
