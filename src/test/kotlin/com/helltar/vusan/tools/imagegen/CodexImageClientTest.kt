package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.config.CodexAuthStore
import com.helltar.vusan.config.ImageRoute
import com.helltar.vusan.config.OpenAiImageConfig
import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexImageClientTest {

    private val config =
        OpenAiImageConfig(model = "gpt-image-2", quality = "low", route = ImageRoute.CODEX)

    @Test
    fun `generate targets the codex backend with the chatgpt session`() = runBlocking {
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(imageBytes)

        val http =
            Http.createClient(
                MockEngine { request ->
                    assertEquals("chatgpt.com", request.url.host)
                    assertEquals("/backend-api/codex/images/generations", request.url.encodedPath)

                    // the same cloudflare-whitelisted set the chat traffic uses; a miss here only
                    // fails once deployed to a non-residential ip.
                    assertEquals("codex_cli_rs", request.headers["originator"])
                    assertTrue(request.headers[HttpHeaders.UserAgent].orEmpty().startsWith("codex_cli_rs/"))
                    assertEquals("acct-1", request.headers["ChatGPT-Account-ID"])
                    assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().startsWith("Bearer "))

                    val payload = Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject
                    assertEquals("gpt-image-2", payload["model"]?.jsonPrimitive?.content)
                    assertEquals("a red panda", payload["prompt"]?.jsonPrimitive?.content)

                    // the codex request has no moderation field; sending one risks the whole request
                    assertFalse(payload.containsKey("moderation"))

                    respondJson("""{"data":[{"b64_json":"$encoded"}]}""")
                }
            )

        val bytes = OpenAiImageClient(http, ImageAuth.Codex(store())).generate("a red panda", "1024x1024", config)

        assertContentEquals(imageBytes, bytes)
    }

    @Test
    fun `edit sends json with the source inlined as a data url`() = runBlocking {
        val source = byteArrayOf(10, 20, 30)
        val result = byteArrayOf(5, 6)
        val encoded = Base64.getEncoder().encodeToString(result)

        val http =
            Http.createClient(
                MockEngine { request ->
                    assertEquals("/backend-api/codex/images/edits", request.url.encodedPath)

                    // the codex edit endpoint is JSON, not the platform's multipart upload
                    val payload = Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject
                    val images = payload["images"].let { checkNotNull(it) }.jsonArray

                    assertEquals(1, images.size)
                    assertEquals(
                        "data:image/png;base64,${Base64.getEncoder().encodeToString(source)}",
                        images[0].jsonObject["image_url"]?.jsonPrimitive?.content
                    )
                    assertEquals("make it blue", payload["prompt"]?.jsonPrimitive?.content)
                    assertEquals("gpt-image-2", payload["model"]?.jsonPrimitive?.content)

                    // gpt-image-2 infers the output size from the source, so asking for one is meaningless
                    assertFalse(payload.containsKey("size"))

                    // gpt-image-2 always edits at high input fidelity and takes no such parameter
                    assertFalse(payload.containsKey("input_fidelity"))
                    assertFalse(payload.containsKey("moderation"))

                    respondJson("""{"data":[{"b64_json":"$encoded"}]}""")
                }
            )

        val bytes =
            OpenAiImageClient(http, ImageAuth.Codex(store()))
                .edit("make it blue", listOf(SourceImage(source, "in.png", "image/png")), "1024x1024", config)

        assertContentEquals(result, bytes)
    }

    @Test
    fun `edit inlines every source image as its own data url`() = runBlocking {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(5, 6))
        var images = 0

        val http =
            Http.createClient(
                MockEngine { request ->
                    val payload = Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject
                    images = payload["images"].let { checkNotNull(it) }.jsonArray.size

                    respondJson("""{"data":[{"b64_json":"$encoded"}]}""")
                }
            )

        OpenAiImageClient(http, ImageAuth.Codex(store()))
            .edit(
                "put them together",
                listOf(
                    SourceImage(byteArrayOf(1), "one.png", "image/png"),
                    SourceImage(byteArrayOf(2), "two.png", "image/png")
                ),
                "1024x1024",
                config
            )

        assertEquals(2, images)
    }

    @Test
    fun `a refusal carrying only the safety wording still reads as a moderation block`() = runBlocking {
        val http =
            Http.createClient(
                MockEngine {
                    respond(
                        content = """{"error":{"message":"Your request was rejected as a result of our safety system."}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )

        val blocked = assertFailsWith<ImageModerationBlocked> {
            OpenAiImageClient(http, ImageAuth.Codex(store())).generate("a portrait", "1024x1024", config)
        }

        assertEquals(ImageModerationStage.UNKNOWN, blocked.stage)
        assertTrue(blocked.categories.isEmpty())
    }
}

private fun MockRequestHandleScope.respondJson(body: String) =
    respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    )

private fun store(): CodexAuthStore =
    CodexAuthStore(Http.createClient(MockEngine { error("no refresh expected") }), signedInAuthFile())

private fun signedInAuthFile(): Path {
    val exp = Instant.now().plusSeconds(3600).epochSecond
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":$exp}""".toByteArray())
    val token = "header.$payload.signature"

    val file = Files.createTempDirectory("codex").resolve("auth.json")
    file.writeText(
        """{"tokens":{"id_token":"$token","access_token":"$token","refresh_token":"r","account_id":"acct-1"}}"""
    )

    return file
}
