package com.helltar.vusan.tools.voice

import com.helltar.vusan.config.ElevenLabsTtsConfig
import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ElevenLabsTtsClientTest {

    private val config = ElevenLabsTtsConfig(
        model = "eleven_v3",
        voiceId = "voice-test",
        outputFormat = "mp3_44100_128"
    )

    @Test
    fun `synthesize sends ElevenLabs speech request and returns audio bytes`() = runBlocking {
        val responseBytes = byteArrayOf(1, 2, 3)
        val http =
            Http.createClient(
                MockEngine { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("https", request.url.protocol.name)
                    assertEquals("api.elevenlabs.io", request.url.host)
                    assertEquals("/v1/text-to-speech/${config.voiceId}", request.url.encodedPath)
                    assertEquals(config.outputFormat, request.url.parameters["output_format"])
                    assertEquals("sk-test", request.headers["xi-api-key"])

                    val body = assertIs<TextContent>(request.body)
                    assertTrue(body.contentType.toString().startsWith(ContentType.Application.Json.toString()))
                    val payload = Json.parseToJsonElement(body.text).jsonObject

                    assertEquals("[whispers] Hello there", payload["text"]?.jsonPrimitive?.content)
                    assertEquals(config.model, payload["model_id"]?.jsonPrimitive?.content)

                    respond(
                        content = ByteReadChannel(responseBytes),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "audio/mpeg")
                    )
                }
            )
        val client = ElevenLabsTtsClient(http, "sk-test")

        val bytes = client.synthesize("[whispers] Hello there", config)

        assertContentEquals(responseBytes, bytes)
        http.close()
    }

    @Test
    fun `synthesize reports sanitized error for non-success response`() = runBlocking {
        val http =
            Http.createClient(
                MockEngine {
                    respond(
                        content = """{"error":{"message":"raw provider payload"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        val client = ElevenLabsTtsClient(http, "sk-test")

        val error = assertFailsWith<IllegalStateException> {
            client.synthesize("Hello there", config)
        }

        assertEquals("HTTP 400 from api.elevenlabs.io", error.message)
        assertFalse(error.message.orEmpty().contains("raw provider payload"))
        http.close()
    }
}
