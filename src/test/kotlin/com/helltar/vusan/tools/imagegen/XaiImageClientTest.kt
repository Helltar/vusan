package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.config.ImageProviderConfig
import com.helltar.vusan.infra.Http
import com.helltar.vusan.infra.HttpStatusException
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class XaiImageClientTest {

    private val imageBytes = byteArrayOf(9, 8, 7, 6)

    private class Probe {
        var path: String? = null
        var auth: String? = null
        var body: JsonObject = JsonObject(emptyMap())
    }

    private fun client(
        probe: Probe = Probe(),
        config: ImageProviderConfig.Xai = ImageProviderConfig.Xai(apiKey = "xai-test"),
        status: HttpStatusCode = HttpStatusCode.OK,
        answer: String = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(imageBytes)}","mime_type":"image/jpeg"}],"usage":{"cost_in_usd_ticks":400000000}}""",
    ): XaiImageClient {
        val http =
            Http.createClient(
                MockEngine { request ->
                    probe.path = "${request.url.host}${request.url.encodedPath}"
                    probe.auth = request.headers[HttpHeaders.Authorization]
                    probe.body = Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject

                    respond(answer, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                },
            )

        return XaiImageClient(http, config.apiKey, config.model, config.quality)
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun `generate asks for base64 in the framing's aspect ratio and decodes the picture`() = runBlocking {
        val probe = Probe()
        val bytes = client(probe).generate("a lighthouse at dusk", ImageFraming.BANNER)

        assertContentEquals(imageBytes, bytes)
        assertEquals("api.x.ai/v1/images/generations", probe.path)
        assertEquals("Bearer xai-test", probe.auth)
        assertEquals("grok-imagine-image-2.0", probe.body.text("model"))
        assertEquals("a lighthouse at dusk", probe.body.text("prompt"))
        assertEquals("16:9", probe.body.text("aspect_ratio"))
        assertEquals("b64_json", probe.body.text("response_format"))
        assertFalse("resolution" in probe.body)
    }

    @Test
    fun `auto quality and auto framing are left out for xai to choose`() = runBlocking {
        val auto = Probe()
        client(auto).generate("x", ImageFraming.AUTO)

        assertFalse("quality" in auto.body)
        assertFalse("aspect_ratio" in auto.body)

        val pinned = Probe()
        client(pinned, ImageProviderConfig.Xai(apiKey = "xai-test", quality = "low")).generate("x", ImageFraming.PORTRAIT)

        assertEquals("low", pinned.body.text("quality"))
        assertEquals("2:3", pinned.body.text("aspect_ratio"))
    }

    @Test
    fun `one source travels as image and several as images, in order, as data urls`() = runBlocking {
        val single = Probe()
        client(single).edit("make it winter", listOf(SourceImage(byteArrayOf(1, 2), "a.png", "image/png")), ImageFraming.AUTO)

        assertEquals("api.x.ai/v1/images/edits", single.path)
        assertEquals("data:image/png;base64,AQI=", single.body["image"]?.jsonObject?.text("url"))
        assertFalse("images" in single.body)

        val several = Probe()
        val sources = listOf(SourceImage(byteArrayOf(1), "a.jpg", "image/jpeg"), SourceImage(byteArrayOf(2), "b.webp", "image/webp"))
        client(several).edit("put them together", sources, ImageFraming.SQUARE)

        val urls = assertNotNull(several.body["images"]).jsonArray.map { it.jsonObject.text("url") }

        assertEquals(listOf("data:image/jpeg;base64,AQ==", "data:image/webp;base64,Ag=="), urls)
        assertFalse("image" in several.body)
        assertEquals("1:1", several.body.text("aspect_ratio"))
    }

    @Test
    fun `a moderated 400 is a refusal, any other 400 stays a failed call`() = runBlocking {
        val moderated =
            client(
                status = HttpStatusCode.BadRequest,
                answer = """{"code":"imagine:content-moderated","error":"Generated image rejected by content moderation."}""",
            )

        assertFailsWith<ImageModerationBlocked> { moderated.generate("x", ImageFraming.SQUARE) }

        val invalid =
            client(
                status = HttpStatusCode.BadRequest,
                answer = """{"code":"invalid-argument","error":"This model only supports the following quality value(s): low, medium, auto."}""",
            )

        val failed = assertFailsWith<HttpStatusException> { invalid.generate("x", ImageFraming.SQUARE) }

        assertEquals(400, failed.status)
    }

    @Test
    fun `a picture withheld by moderation is a refusal at the output`() = runBlocking {
        val withheld = client(answer = """{"data":[{"respect_moderation":false}]}""")

        val e = assertFailsWith<ImageModerationBlocked> { withheld.generate("x", ImageFraming.SQUARE) }

        assertEquals(ImageModerationStage.OUTPUT, e.stage)
    }
}
