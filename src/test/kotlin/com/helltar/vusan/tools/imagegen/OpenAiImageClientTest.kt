package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.config.OpenAiImageConfig
import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class OpenAiImageClientTest {

    private val config = OpenAiImageConfig(model = "gpt-image-1.5", quality = "medium")

    @Test
    fun `generate posts image request and decodes base64 image`() = runBlocking {
        val imageBytes = byteArrayOf(9, 8, 7, 6)
        val encoded = Base64.getEncoder().encodeToString(imageBytes)
        val http =
            Http.createClient(
                MockEngine { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("https", request.url.protocol.name)
                    assertEquals("api.openai.com", request.url.host)
                    assertEquals("/v1/images/generations", request.url.encodedPath)
                    assertEquals("Bearer sk-test", request.headers[HttpHeaders.Authorization])

                    val body = assertIs<TextContent>(request.body)
                    val payload = Json.parseToJsonElement(body.text).jsonObject

                    assertEquals("gpt-image-1.5", payload["model"]?.jsonPrimitive?.content)
                    assertEquals("a red panda astronaut", payload["prompt"]?.jsonPrimitive?.content)
                    assertEquals("1536x1024", payload["size"]?.jsonPrimitive?.content)
                    assertEquals("medium", payload["quality"]?.jsonPrimitive?.content)
                    // gpt-image-1 rejects response_format, so it must never be sent.
                    assertFalse(payload.containsKey("response_format"))

                    respond(
                        content = """{"data":[{"b64_json":"$encoded"}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        val client = OpenAiImageClient(http, "sk-test")

        val bytes = client.generate("a red panda astronaut", "1536x1024", config)

        assertContentEquals(imageBytes, bytes)
        http.close()
    }

    @Test
    fun `generate reports error with body preview for non-success response`() = runBlocking {
        val http =
            Http.createClient(
                MockEngine {
                    respond(
                        content = """{"error":{"message":"content policy"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        val client = OpenAiImageClient(http, "sk-test")

        val error = assertFailsWith<IllegalStateException> {
            client.generate("forbidden", "1024x1024", config)
        }

        assertEquals(
            """HTTP 400 from api.openai.com: {"error":{"message":"content policy"}}""",
            error.message
        )
        http.close()
    }

    @Test
    fun `generate fails when response carries no image data`() = runBlocking {
        val http =
            Http.createClient(
                MockEngine {
                    respond(
                        content = """{"data":[]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        val client = OpenAiImageClient(http, "sk-test")

        assertFailsWith<IllegalStateException> {
            client.generate("anything", "1024x1024", config)
        }
        http.close()
    }
}
